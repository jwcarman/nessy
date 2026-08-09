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
package org.jwcarman.nessy.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.AgentEventListener;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.internal.ToolInvoker;
import org.jwcarman.nessy.spi.model.AgentConfig;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.session.SessionStore;

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

  /**
   * Runs one turn to completion and persists it.
   *
   * <p>Durability contract: the most recent state the run reached is saved on <em>every</em> exit
   * path, including an exception. A provider socket reset, a throwing listener, or a tool that asks
   * to park would otherwise unwind past the save and discard the user's message along with every
   * token streamed before the failure, while the store still held pre-run state. Progress is
   * published into a holder as the run advances, so what survives is what actually happened rather
   * than only what was already durable.
   */
  @Override
  public RunOutcome run(SessionId id, Event input) {
    AtomicReference<SessionState> progress =
        new AtomicReference<>(store.load(id).orElseGet(() -> SessionState.newSession(id)));
    try {
      return new RunOutcome.Completed(feed(progress, progress.get(), input));
    } finally {
      store.save(progress.get());
    }
  }

  @Override
  public RunOutcome resume(SessionId id, ParkToken token, Event resolution) {
    throw new UnsupportedOperationException(
        "InProcessEngine never parks, so there is nothing to resume. Use DurableEngine.");
  }

  /** Reduces one event and tells the listeners, without performing its effects. */
  private Step reduceAndNotify(SessionState state, Event event) {
    Step step = reducer.reduce(state, event);
    for (AgentEventListener listener : listeners) {
      listener.onEvent(step.state().id(), event, step.state());
    }
    return step;
  }

  /**
   * Reduces one event, tells the listeners, then performs whatever it asked for.
   *
   * <p>{@code progress} is the run's latest-known-state holder. Every advance publishes into it so
   * {@link #run} can persist partial work even when the recursion unwinds on an exception.
   */
  private SessionState feed(
      AtomicReference<SessionState> progress, SessionState state, Event event) {
    Step step = reduceAndNotify(state, event);
    SessionState next = step.state();
    progress.set(next);
    for (Effect effect : step.effects()) {
      next = perform(progress, next, effect);
      progress.set(next);
    }
    return next;
  }

  private SessionState perform(
      AtomicReference<SessionState> progress, SessionState state, Effect effect) {
    return switch (effect) {
      case Effect.CallModel ignored -> callModel(progress, state);
      case Effect.RequestApproval request -> feed(progress, state, decide(state, request.call()));
      case Effect.ExecuteTool execute -> feed(progress, state, executeTool(state, execute.call()));
    };
  }

  /**
   * Streams one model turn, deferring its effects until after the stream closes.
   *
   * <p>A tool round-trip triggered by the terminal event would otherwise open the next {@code
   * ModelStream} while this one is still held open by the enclosing try-with-resources, leaking a
   * live connection per round-trip. Listeners are still notified as each chunk arrives, from inside
   * the loop, so streaming stays live; only effects — of which only the terminal event has any —
   * are deferred.
   */
  private SessionState callModel(AtomicReference<SessionState> progress, SessionState state) {
    SessionState current = state;
    List<Effect> deferred = new ArrayList<>();
    try (ModelStream stream = provider.stream(requestFor(current))) {
      for (ModelEvent modelEvent : stream) {
        Step step = reduceAndNotify(current, translate(modelEvent));
        current = step.state();
        progress.set(current);
        deferred.addAll(step.effects());
      }
    }
    for (Effect effect : deferred) {
      current = perform(progress, current, effect);
      progress.set(current);
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
        config.capabilities());
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
    ApprovalRequest request =
        new ApprovalRequest(state.id(), call, describeForApproval(tool, call));
    return new Event.ApprovalDecided(call, resolve(approver.approve(request), "approver"));
  }

  /**
   * Renders a call for the approval prompt, without letting malformed arguments blow up the
   * session.
   *
   * <p>{@code describe} binds the call's raw JSON to the tool's input record, which throws on
   * arguments the record cannot accept — the same failure {@link #executeTool} recovers from. A
   * tool that does not require approval already turns that failure into a model-visible error; a
   * tool that does should get the same chance rather than losing the whole session before it ever
   * reaches execution. The raw call is still shown, so a human reviewing the prompt can see the
   * arguments are malformed rather than being told they parsed.
   */
  private String describeForApproval(Tool<?> tool, ToolCall call) {
    try {
      return invoker.describe(tool, call);
    } catch (RuntimeException e) {
      return call.name() + "(" + call.arguments() + ")";
    }
  }

  private Event executeTool(SessionState state, ToolCall call) {
    Optional<Tool<?>> found = tools.find(call.name());
    if (found.isEmpty()) {
      return new Event.ToolFinished(call, ToolResult.error("No such tool: " + call.name()));
    }
    Awaited<ToolResult> awaited;
    try {
      awaited = invoker.invoke(found.get(), call, new ToolContext(state.id()));
    } catch (RuntimeException e) {
      // Factor 9: the model sees a compact error and gets to recover. It
      // never sees a stack trace, and the loop never dies on a bad tool.
      return new Event.ToolFinished(call, ToolResult.error(describe(e)));
    }
    // Outside the catch: a tool that parks is a configuration error, not a
    // runtime one, and must fail loudly rather than becoming model-visible noise.
    return new Event.ToolFinished(call, resolve(awaited, "tool " + call.name()));
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
