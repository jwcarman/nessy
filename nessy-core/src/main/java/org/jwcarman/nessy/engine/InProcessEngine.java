/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.approval.ApprovalRequest;
import org.jwcarman.nessy.approval.Approver;
import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;
import org.jwcarman.nessy.core.Effect;
import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.Reducer;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import org.jwcarman.nessy.core.Step;
import org.jwcarman.nessy.core.ToolCall;
import org.jwcarman.nessy.core.ToolResult;
import org.jwcarman.nessy.model.ModelEvent;
import org.jwcarman.nessy.model.ModelProvider;
import org.jwcarman.nessy.model.ModelRequest;
import org.jwcarman.nessy.model.ModelStream;
import org.jwcarman.nessy.session.SessionStore;
import org.jwcarman.nessy.tool.Tool;
import org.jwcarman.nessy.tool.ToolContext;
import org.jwcarman.nessy.tool.ToolInvoker;
import org.jwcarman.nessy.tool.ToolRegistry;

/**
 * The default engine: blocking calls on whatever thread you hand it, and it never parks.
 *
 * <p>Correct for a CLI, a test, or any front-end that owns the session for its whole life. Run it
 * on a virtual thread and a human taking an hour to approve something costs a few hundred bytes of
 * heap.
 *
 * <p>A tool or approver that returns {@link Awaited.Parked} is a configuration error here, not a
 * runtime condition: this engine has nowhere to park a session to. It says so loudly rather than
 * hanging.
 */
public final class InProcessEngine implements ExecutionEngine {

  private final ModelProvider provider;
  private final ToolRegistry tools;
  private final Approver approver;
  private final SessionStore store;
  private final List<AgentEventListener> listeners;
  private final Reducer reducer;
  private final AgentConfig config;
  private final ToolInvoker invoker;

  public InProcessEngine(
      ModelProvider provider,
      ToolRegistry tools,
      Approver approver,
      SessionStore store,
      List<AgentEventListener> listeners,
      Reducer reducer,
      AgentConfig config,
      ObjectMapper mapper) {
    this.provider = provider;
    this.tools = tools;
    this.approver = approver;
    this.store = store;
    this.listeners = List.copyOf(listeners);
    this.reducer = reducer;
    this.config = config;
    this.invoker = new ToolInvoker(mapper);
  }

  @Override
  public RunOutcome run(SessionId id, Event input) {
    SessionState state = store.load(id).orElseGet(() -> SessionState.newSession(id));
    SessionState finished = feed(state, input);
    store.save(finished);
    return new RunOutcome.Completed(finished);
  }

  @Override
  public RunOutcome resume(SessionId id, ParkToken token, Event resolution) {
    throw new UnsupportedOperationException(
        "InProcessEngine never parks, so there is nothing to resume. Use DurableEngine.");
  }

  /** Reduces one event, tells the listeners, then performs whatever it asked for. */
  private SessionState feed(SessionState state, Event event) {
    Step step = reducer.reduce(state, event);
    SessionState next = step.state();
    for (AgentEventListener listener : listeners) {
      listener.onEvent(next.id(), event, next);
    }
    for (Effect effect : step.effects()) {
      next = perform(next, effect);
    }
    return next;
  }

  private SessionState perform(SessionState state, Effect effect) {
    return switch (effect) {
      case Effect.CallModel ignored -> callModel(state);
      case Effect.RequestApproval request -> feed(state, decide(state, request.call()));
      case Effect.ExecuteTool execute -> feed(state, run(state, execute.call()));
    };
  }

  private SessionState callModel(SessionState state) {
    SessionState current = state;
    try (ModelStream stream = provider.stream(requestFor(current))) {
      for (ModelEvent modelEvent : stream) {
        current = feed(current, translate(modelEvent));
      }
    }
    return current;
  }

  private ModelRequest requestFor(SessionState state) {
    return new ModelRequest(
        state.messages(),
        config.systemPrompt(),
        config.model(),
        config.maxTokens(),
        tools.specs(),
        Set.of());
  }

  private static Event translate(ModelEvent event) {
    return switch (event) {
      case ModelEvent.TextChunk chunk -> new Event.TextDelta(chunk.text());
      case ModelEvent.ToolUseEmitted emitted -> new Event.ToolCallRequested(emitted.call());
      case ModelEvent.TurnEnded ended -> new Event.ModelTurnEnded(ended.reason());
    };
  }

  /**
   * Answers the approval question for one call.
   *
   * <p>A tool that does not require approval is allowed here without troubling the approver. The
   * decision still belongs to the harness — the model has no say in whether it is asked.
   */
  private Event decide(SessionState state, ToolCall call) {
    Optional<Tool<?>> found = tools.find(call.name());
    if (found.isEmpty()) {
      // Resolved as an allow so the missing-tool error surfaces once, in
      // execution, rather than as two different errors in two places.
      return new Event.ApprovalDecided(call, Decision.allow());
    }
    Tool<?> tool = found.get();
    if (!tool.requiresApproval()) {
      return new Event.ApprovalDecided(call, Decision.allow());
    }
    ApprovalRequest request = new ApprovalRequest(state.id(), call, invoker.describe(tool, call));
    return new Event.ApprovalDecided(call, resolve(approver.approve(request), "approver"));
  }

  private Event run(SessionState state, ToolCall call) {
    Optional<Tool<?>> found = tools.find(call.name());
    if (found.isEmpty()) {
      return new Event.ToolFinished(call, ToolResult.error("No such tool: " + call.name()));
    }
    try {
      Awaited<ToolResult> awaited = invoker.invoke(found.get(), call, new ToolContext(state.id()));
      return new Event.ToolFinished(call, resolve(awaited, "tool " + call.name()));
    } catch (RuntimeException e) {
      // Factor 9: the model sees a compact error and gets to recover. It
      // never sees a stack trace, and the loop never dies on a bad tool.
      return new Event.ToolFinished(call, ToolResult.error(describe(e)));
    }
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    return message == null
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + message;
  }

  private static <T> T resolve(Awaited<T> awaited, String what) {
    return switch (awaited) {
      case Awaited.Ready<T> ready -> ready.value();
      case Awaited.Parked<T> ignored ->
          throw new UnsupportedOperationException(
              "InProcessEngine cannot park, but the " + what + " asked to. Use DurableEngine.");
    };
  }
}
