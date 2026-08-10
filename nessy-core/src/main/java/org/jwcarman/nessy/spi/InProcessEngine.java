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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.compaction.CompactionStrategy;
import org.jwcarman.nessy.api.event.CompactionFailed;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.SessionState;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.internal.EngineObservations;
import org.jwcarman.nessy.internal.ToolInvoker;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.session.SessionStore;
import org.jwcarman.nessy.spi.session.TranscriptEntry;
import org.jwcarman.nessy.spi.session.TranscriptStore;

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
  private final Map<String, ToolGrant> grants;
  private final Approver approver;
  private final SessionStore store;
  private final EventHub hub;
  private final Reducer reducer;
  private final ModelSettings config;
  private final ToolInvoker invoker;
  private final ObservationRegistry observations;
  private final ContextAssembler contextAssembler;
  private final TranscriptStore transcript;

  public InProcessEngine(
      ModelProvider provider,
      ToolRegistry tools,
      Map<String, ToolGrant> grants,
      Approver approver,
      SessionStore store,
      EventHub hub,
      Reducer reducer,
      ModelSettings config,
      ObjectMapper mapper,
      ObservationRegistry observations,
      ContextAssembler contextAssembler,
      TranscriptStore transcript) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.grants = Map.copyOf(Objects.requireNonNull(grants, "grants must not be null"));
    requireEveryRegisteredToolIsGranted(this.tools, this.grants);
    this.approver = Objects.requireNonNull(approver, "approver must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.hub = Objects.requireNonNull(hub, "hub must not be null");
    this.reducer = Objects.requireNonNull(reducer, "reducer must not be null");
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.invoker = new ToolInvoker(Objects.requireNonNull(mapper, "mapper must not be null"));
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    this.contextAssembler =
        Objects.requireNonNull(contextAssembler, "contextAssembler must not be null");
    this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
  }

  /**
   * The wiring-time belt for the {@code tools}/{@code grants} pair: every tool {@code
   * tools.specs()} advertises to the model must have a grant, or the model could be offered a tool
   * whose authority was never decided. This is the ordinary desync — {@code AgentBuilder} always
   * derives one map from the other, so only hand-rolled engine construction can trip it — caught
   * loudly at construction rather than silently at the chokepoint. It does not catch an exotic
   * {@link ToolRegistry} whose {@code find(name)} resolves names {@code specs()} never advertised;
   * {@link #decide} carries its own guard for that case.
   */
  private static void requireEveryRegisteredToolIsGranted(
      ToolRegistry tools, Map<String, ToolGrant> grants) {
    for (ToolSpec spec : tools.specs()) {
      if (!grants.containsKey(spec.name())) {
        throw new IllegalArgumentException("no grant for tool: " + spec.name());
      }
    }
  }

  /**
   * Runs one turn to completion and persists it.
   *
   * <p>Durability contract: the most recent state the run reached is saved on <em>every</em> exit
   * path, including an exception. A provider socket reset or a tool that asks to park would
   * otherwise unwind past the save and discard the user's message along with every token streamed
   * before the failure, while the store still held pre-run state. Progress is published into a
   * holder as the run advances, so what survives is what actually happened rather than only what
   * was already durable.
   */
  @Override
  public RunOutcome run(SessionId id, Event input) {
    Observation observation = EngineObservations.run(observations, id);
    try (var _ = observation.openScope()) {
      AtomicReference<SessionState> progress =
          new AtomicReference<>(store.load(id).orElseGet(() -> SessionState.newSession(id)));
      try {
        return new RunOutcome.Completed(feed(progress, progress.get(), input));
      } finally {
        store.save(progress.get());
      }
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }

  @Override
  public RunOutcome resume(SessionId id, ParkToken token, Event resolution) {
    throw new UnsupportedOperationException(
        "InProcessEngine never parks, so there is nothing to resume. Use DurableEngine.");
  }

  /** Reduces one event and tells the hub, without performing its effects. */
  private Step reduceAndNotify(SessionState state, Event event) {
    Step step = reducer.reduce(state, event);
    journal(state, step.state(), event);
    hub.emit(new SessionEvent(step.state().id(), event, step.state()));
    return step;
  }

  /**
   * Appends every message born by this one reduce to {@link #transcript}, in birth order.
   *
   * <p>This is the single choke point: {@link #reduceAndNotify} is called from both {@link #feed}
   * and the streaming loop in {@link #callModel}, so covering it here covers every arm that ever
   * adopts a reducer's output.
   *
   * <p>Two shapes of birth:
   *
   * <ul>
   *   <li>Normal growth — {@code before.messages()} is a proper prefix of {@code after.messages()}
   *       — appends the tail delta. The flushed assistant message of a completed model turn carries
   *       that turn's usage ({@code event} is {@link Event.ModelTurnEnded}); every other newborn
   *       message (a user message, a flushed tool-results message) carries {@link Usage#zero()}.
   *   <li>Compaction — {@code after.generation()} advanced — appends whatever messages of {@code
   *       after} are not present (by value) in {@code before}, comparing by equality rather than
   *       position since a custom strategy is free to keep some originals and drop others. For the
   *       summarizing default that is exactly the one summary message. Every newborn here carries
   *       the {@link Event.Compacted} event's spend. Survivors are never re-appended.
   * </ul>
   *
   * <p>No {@code try}/{@code catch} here on purpose: see {@link TranscriptStore}'s strict-append
   * contract. A throwing store propagates out of this method, out of {@link #reduceAndNotify}, and
   * ultimately out of {@link #run} — {@code run}'s own {@code finally} still saves whatever
   * progress reached the holder before this reduce.
   */
  private void journal(SessionState before, SessionState after, Event event) {
    if (after.generation() != before.generation()) {
      Usage spend = event instanceof Event.Compacted compacted ? compacted.spend() : Usage.zero();
      List<Message> survivors = new ArrayList<>(before.messages());
      for (Message message : after.messages()) {
        if (!survivors.remove(message)) {
          transcript.append(after.id(), new TranscriptEntry(message, spend));
        }
      }
      return;
    }
    if (after.messages().size() <= before.messages().size()) {
      return;
    }
    Usage usage = event instanceof Event.ModelTurnEnded ended ? ended.usage() : Usage.zero();
    for (Message message :
        after.messages().subList(before.messages().size(), after.messages().size())) {
      transcript.append(after.id(), new TranscriptEntry(message, usage));
    }
  }

  /**
   * Reduces one event, tells the hub, then performs whatever it asked for.
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
      case Effect.CallModel _ -> callModel(progress, state);
      case Effect.RequestApproval(ToolCall call) -> feed(progress, state, decide(state, call));
      case Effect.ExecuteTool(ToolCall call) -> feed(progress, state, executeTool(state, call));
      case Effect.Compact compaction -> compact(progress, state, compaction);
    };
  }

  /**
   * Performs a compaction by handing the whole working set to {@code reducer.compaction()}, then
   * feeds the one event it produces.
   *
   * <p>Unlike {@link #callModel}, nothing here streams live into the hub: whatever the strategy
   * does internally (a model call, for the summarizing default) is not conversation the model or a
   * listener needs to see chunk by chunk, only the finished result the reducer folds into the
   * transcript. The whole attempt — the strategy's {@code compact()}, the result's validation, and
   * the resulting {@link Event#Compacted} or {@link Event#CompactionSkipped} feed — runs inside one
   * {@code nessy.compaction} observation, matching the F2 convention used everywhere else in this
   * engine: a caught failure marks the observation with {@link Observation#error(Throwable)} rather
   * than letting it escape, since a failed compaction is recoverable and the turn must proceed
   * uncompacted.
   *
   * <p>{@link Context#of} validates the strategy's result before it ever reaches the reducer: a
   * strategy that hands back a pair-breaking working set is treated as a failure here, not a
   * corruption the reducer has to detect later.
   *
   * <p>As in {@link #callModel}, the resulting event is built inside the observation scope but fed
   * to the reducer only after the scope closes: {@code feed} can trigger the next model turn, and
   * that follow-on work must not nest under {@code nessy.compaction} or be timed as part of it.
   */
  private SessionState compact(
      AtomicReference<SessionState> progress, SessionState state, Effect.Compact effect) {
    Observation observation = EngineObservations.compaction(observations);
    Event event;
    try (var _ = observation.openScope()) {
      try {
        CompactionStrategy.Result result = reducer.compaction().compact(effect.workingSet());
        Context.of(result.workingSet());
        event = new Event.Compacted(result.workingSet(), result.spend());
      } catch (RuntimeException e) {
        observation.error(e);
        String reason = describe(e);
        hub.emit(new CompactionFailed(state.id(), reason));
        event = new Event.CompactionSkipped(reason);
      }
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
    return feed(progress, state, event);
  }

  /**
   * Streams one model turn, deferring its effects until after the stream closes.
   *
   * <p>A tool round-trip triggered by the terminal event would otherwise open the next {@code
   * ModelStream} while this one is still held open by the enclosing try-with-resources, leaking a
   * live connection per round-trip. The hub is still notified as each chunk arrives, from inside
   * the loop, so streaming stays live; only effects — of which only the terminal event has any —
   * are deferred.
   *
   * <p>{@code turn} wraps the whole method; {@code modelCall} wraps only the stream-consumption
   * try-block, since deferred effects run after the stream has already closed. Deferred effects
   * that trigger another turn (a tool round-trip) recurse back into this method, so a nested {@code
   * turn} observation parents under the turn that caused it — the recursion is the causality.
   */
  private SessionState callModel(AtomicReference<SessionState> progress, SessionState state) {
    Observation turn = EngineObservations.turn(observations);
    try (var _ = turn.openScope()) {
      SessionState current = state;
      List<Effect> deferred = new ArrayList<>();
      Observation modelCall = EngineObservations.modelCall(observations, config.model());
      try (var _ = modelCall.openScope();
          ModelStream stream = provider.stream(requestFor(current))) {
        for (ModelEvent modelEvent : stream) {
          Step step = reduceAndNotify(current, translate(modelEvent));
          current = step.state();
          progress.set(current);
          deferred.addAll(step.effects());
          if (modelEvent instanceof ModelEvent.TurnEnded ended) {
            EngineObservations.recordUsage(modelCall, ended.usage());
          }
        }
      } catch (RuntimeException e) {
        modelCall.error(e);
        throw e;
      } finally {
        modelCall.stop();
      }
      for (Effect effect : deferred) {
        current = perform(progress, current, effect);
        progress.set(current);
      }
      return current;
    } catch (RuntimeException e) {
      turn.error(e);
      throw e;
    } finally {
      turn.stop();
    }
  }

  /**
   * Assembles the request for one conversational model call by delegating to {@link
   * #contextAssembler} — the same instance {@code Agent.contextFor} consults, so the two never
   * disagree about what a call sees.
   *
   * <p>The compaction/summarization path is deliberately not routed through here: {@link #compact}
   * hands the strategy its own working set directly, so a memory is never consulted for that call.
   */
  private ModelRequest requestFor(SessionState state) {
    Context projected = contextAssembler.assemble(state);
    return new ModelRequest(
        projected,
        config.systemPrompt(),
        config.model(),
        config.maxTokens(),
        tools.specs(),
        config.capabilities(),
        null);
  }

  private static Event translate(ModelEvent event) {
    return switch (event) {
      case ModelEvent.TextChunk(String text) -> new Event.TextDelta(text);
      case ModelEvent.ThinkingChunk(String text) -> new Event.ThinkingDelta(text);
      case ModelEvent.ThinkingSigned(String signature) -> new Event.ThinkingSigned(signature);
      case ModelEvent.RedactedThinkingEmitted(String data) ->
          new Event.RedactedThinkingArrived(data);
      case ModelEvent.ToolUseEmitted(ToolCall call) -> new Event.ToolCallRequested(call);
      case ModelEvent.TurnEnded(StopReason reason, Usage usage) ->
          new Event.ModelTurnEnded(reason, usage);
    };
  }

  /**
   * Answers the approval question for one call by consulting its grant — the harness's one
   * authority chokepoint.
   *
   * <p>{@link Tool#requiresApproval()} plays no part here: {@link ToolGrant#grant} is the only
   * place that reads it, deriving the default policy at grant construction. From here on the
   * grant's {@link org.jwcarman.nessy.api.tool.UsagePolicy} alone decides whether the approver is
   * ever asked.
   *
   * <p>A missing grant splits into two cases. A call to a tool {@link #tools} does not know at all
   * is allowed through here — {@link #executeTool} then turns it into the one, model-visible "no
   * such tool" error, so a missing grant never surfaces as a second, different failure. A call to a
   * tool {@link #tools} <em>does</em> know — registered, but with no entry in {@link #grants} — is
   * a wiring error, not something the model did: {@link #requireEveryRegisteredToolIsGranted}
   * catches the ordinary version of this at construction, but an exotic registry whose {@code
   * find(name)} resolves beyond its own {@code specs()} can still reach here, so it is denied
   * outright rather than allowed to run ungated.
   */
  private Event decide(SessionState state, ToolCall call) {
    ToolGrant grant = grants.get(call.name());
    if (grant == null) {
      if (tools.find(call.name()).isPresent()) {
        return new Event.ApprovalDecided(
            call, new Decision.Deny("no grant for tool: " + call.name()));
      }
      return new Event.ApprovalDecided(call, Decision.allow());
    }
    PolicyDecision decision = evaluate(grant.policy(), call, state);
    return switch (decision) {
      case PolicyDecision.Allow _ -> new Event.ApprovalDecided(call, Decision.allow());
      case PolicyDecision.Deny(String reason) ->
          new Event.ApprovalDecided(call, new Decision.Deny(reason));
      case PolicyDecision.RequireApproval _ -> requestApproval(state, grant.tool(), call);
    };
  }

  /**
   * Runs one policy, fail-closed: a policy is supposed to be pure and total, but a broken or
   * incomplete one must never become an allow. A thrown {@link RuntimeException} becomes a {@link
   * PolicyDecision.Deny} carrying the same description {@link #describe(RuntimeException)} gives
   * every other harness-caught failure; a {@code null} result — a policy that returns nothing
   * rather than throwing — is treated the same way, with its own reason, since a silently missing
   * decision is just as much a broken policy as a thrown exception.
   */
  private static PolicyDecision evaluate(UsagePolicy policy, ToolCall call, SessionState state) {
    try {
      PolicyDecision decision = policy.evaluate(call, state);
      return decision != null ? decision : new PolicyDecision.Deny("policy returned no decision");
    } catch (RuntimeException e) {
      return new PolicyDecision.Deny(describe(e));
    }
  }

  /** The existing human-approval flow, unchanged: only {@link #decide} routes into it now. */
  private Event requestApproval(SessionState state, Tool<?> tool, ToolCall call) {
    ApprovalRequest request =
        new ApprovalRequest(state.id(), call, describeForApproval(tool, call));
    Observation observation = EngineObservations.approvalWait(observations, tool.name());
    Awaited<Decision> decision;
    try (Observation.Scope scope = observation.openScope()) {
      decision = approver.approve(request);
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
    return new Event.ApprovalDecided(call, resolve(decision, "approver"));
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
    Observation observation = EngineObservations.toolCall(observations, call.name(), call.id());
    try (Observation.Scope scope = observation.openScope()) {
      Awaited<ToolResult> awaited;
      try {
        awaited = invoker.invoke(found.get(), call, new ToolContext(state.id(), hub));
      } catch (RuntimeException e) {
        // Factor 9: the model sees a compact error and gets to recover. It
        // never sees a stack trace, and the loop never dies on a bad tool. The
        // span still has to say so: without this, every tool failure reports
        // as a success to anything watching the span.
        observation.error(e);
        ToolResult result = ToolResult.error(describe(e));
        EngineObservations.recordOutcome(observation, result);
        return new Event.ToolFinished(call, result);
      }
      // Outside the catch: a tool that parks is a configuration error, not a
      // runtime one, and must fail loudly rather than becoming model-visible noise.
      ToolResult result = resolve(awaited, "tool " + call.name());
      EngineObservations.recordOutcome(observation, result);
      return new Event.ToolFinished(call, result);
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
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
      case Awaited.Ready<T>(T value) -> value;
      case Awaited.Parked<T> _ ->
          throw new UnsupportedOperationException(
              "InProcessEngine cannot park, but the " + what + " asked to. Use DurableEngine.");
    };
  }
}
