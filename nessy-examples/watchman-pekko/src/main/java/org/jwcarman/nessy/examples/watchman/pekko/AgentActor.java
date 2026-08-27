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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.state.RecoveryCompleted;
import org.apache.pekko.persistence.typed.state.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.state.javadsl.DurableStateBehavior;
import org.apache.pekko.persistence.typed.state.javadsl.Effect;
import org.apache.pekko.persistence.typed.state.javadsl.SignalHandler;

/**
 * The watchman itself: one durable actor per agent id, and the parent of everything a round does.
 *
 * <p>The whole round lifecycle, in the order the command handler reads:
 *
 * <pre>
 *   Observe          Idle          -&gt; CallingModel   ... and ask the desk
 *   ModelReplied     CallingModel  -&gt; WorkingTools   ... and spawn one actor per call
 *                                  -&gt; Idle           ... if the model just wrote its notes
 *   AnswerApproval   WorkingTools  -&gt; WorkingTools   ... PERSIST the decision, then relay, then ack
 *   ToolCallSettled  WorkingTools  -&gt; WorkingTools   ... or back to CallingModel when all settled
 * </pre>
 *
 * <p><b>{@code AnswerApproval} is the one that repays study</b>, because it is where the spike's
 * tidiest result did not survive contact with a real requirement. In the spike an approval answer
 * changed no state at all: it was relayed to a live child and the agent stayed out of it. That is
 * unimplementable here. A human clicks deny, we return 200, the box loses power a millisecond later
 * — and with nothing persisted the denial is gone while the operator believes it landed. So the
 * decision is persisted BEFORE the reply, and the reply is what the HTTP handler waits on.
 *
 * <p>The distinction that survived is worth naming precisely: <b>transitions the machine drives
 * itself</b> need no state (a call moving from running to finished is just an actor stopping),
 * while <b>facts arriving from outside</b> must be persisted before they are acknowledged.
 */
public final class AgentActor extends DurableStateBehavior<AgentActor.Command, TurnState> {

  /** How a runtime asks for this agent to be let go. */
  @FunctionalInterface
  public interface StopRequest {
    void requestStop(String agentId, ActorRef<Command> self);
  }

  public sealed interface Command {}

  /** From the cron: do your rounds. */
  public record Observe(String text, Map<String, String> trace) implements Command {}

  /** From a model worker. */
  public record ModelReplied(ModelReply reply, Map<String, String> trace) implements Command {}

  /** From one of this agent's own tool-call children. */
  public record ToolCallSettled(String callId, String outcome, Map<String, String> trace)
      implements Command {}

  /**
   * From the approvals page. Carries a {@code replyTo} because the HTTP handler must not return 200
   * until this has been written down — see {@link #onAnswerApproval}.
   */
  public record AnswerApproval(
      String callId, boolean approved, String by, String note, ActorRef<Ack> replyTo)
      implements Command {}

  /** What the page is told once the decision is durable. */
  public record Ack(boolean accepted, String detail) {}

  /** From the page and the tests: what does this agent look like right now? */
  public record Inspect(ActorRef<TurnState> replyTo) implements Command {}

  /** From the startup sweep: exists only to bring the actor into memory so recovery can run. */
  public record Wake() implements Command {}

  /** From the world: let go of memory. */
  public record Rest() implements Command {}

  /** From the runtime, in response to {@link Rest}. The only message that ends the actor. */
  public record Stop() implements Command {}

  public static final Stop STOP = new Stop();

  private final ActorContext<Command> context;
  private final String agentId;
  private final Dependencies deps;

  /**
   * Live children by call id. Not persisted, and correctly empty after a restart: there are no
   * children after a restart. Kept as a field because {@code context.getChild(name)} hands back an
   * untyped ref that cannot be told a typed message.
   */
  private final Map<String, ActorRef<ToolCallActor.Command>> callActors = new HashMap<>();

  /**
   * Everything an agent needs that Spring owns. In Typed this is the whole of dependency injection:
   * Spring builds the beans, the Behavior factory takes them. No extension, no producer, no
   * ApplicationContext lookup.
   */
  public record Dependencies(
      ActorRef<ModelDesk.Command> modelDesk,
      ActorRef<ToolWorker.RunTool> tools,
      Traces traces,
      Clock clock,
      Duration approvalTerm) {}

  public static Behavior<Command> create(
      String agentId, Dependencies deps, StopRequest stopRequest) {
    return Behaviors.setup(context -> new AgentActor(context, agentId, deps, stopRequest));
  }

  private final StopRequest stopRequest;

  private AgentActor(
      ActorContext<Command> context, String agentId, Dependencies deps, StopRequest stopRequest) {
    super(PersistenceId.of("Watchman", agentId));
    this.context = context;
    this.agentId = agentId;
    this.deps = deps;
    this.stopRequest = stopRequest;
  }

  @Override
  public TurnState emptyState() {
    return TurnState.Idle.empty();
  }

  @Override
  public CommandHandler<Command, TurnState> commandHandler() {
    return (state, command) ->
        switch (command) {
          case Inspect inspect -> Effect().none().thenRun(() -> inspect.replyTo().tell(state));
          case Wake ignored -> Effect().none();
          case Rest ignored ->
              Effect().none().thenRun(() -> stopRequest.requestStop(agentId, context.getSelf()));
          case Stop ignored -> Effect().none().thenStop();
          case Observe observe -> onObserve(state, observe);
          case ModelReplied replied -> onModelReplied(state, replied);
          case AnswerApproval answer -> onAnswerApproval(state, answer);
          case ToolCallSettled settled -> onToolCallSettled(state, settled);
        };
  }

  private Effect<TurnState> onObserve(TurnState state, Observe observe) {
    if (!(state instanceof TurnState.Idle)) {
      context.getLog().info("[watchman] a round is already in flight; dropping the cron tick");
      return Effect().none();
    }
    var next =
        new TurnState.CallingModel(
            TurnState.plus(state.transcript(), new Turn.User(observe.text())));
    return Effect().persist(next).thenRun(() -> askModel(next, observe.trace()));
  }

  private Effect<TurnState> onModelReplied(TurnState state, ModelReplied replied) {
    if (!(state instanceof TurnState.CallingModel)) {
      return Effect().none();
    }
    return switch (replied.reply()) {
      case ModelReply.Said(String text) ->
          Effect()
              .persist(
                  new TurnState.Idle(
                      TurnState.plus(state.transcript(), new Turn.Assistant(text, List.of()))));
      case ModelReply.Failed(String detail) ->
          Effect()
              .persist(
                  new TurnState.Idle(
                      TurnState.plus(
                          state.transcript(),
                          new Turn.Assistant("the round failed: " + detail, List.of()))));
      case ModelReply.AskedForTools(String preamble, var requests) -> {
        var now = deps.clock().instant();
        var calls =
            requests.stream()
                .map(
                    request ->
                        ToolCallRecord.asked(
                            request.id(),
                            request.tool(),
                            request.argumentsJson(),
                            WatchmanTools.action(request.tool(), request.argumentsJson()),
                            now))
                .toList();
        var next =
            new TurnState.WorkingTools(
                TurnState.plus(state.transcript(), new Turn.Assistant(preamble, requests)), calls);
        yield Effect().persist(next).thenRun(() -> spawnMissing(next, replied.trace()));
      }
    };
  }

  /**
   * Durable ingest. The order is the whole point and it is not negotiable:
   *
   * <ol>
   *   <li>{@code persist} — the decision is on disk;
   *   <li>{@code thenRun} — the live child, if any, is told to get on with it;
   *   <li>{@code thenReply} — and ONLY NOW does the HTTP handler learn it may answer 200.
   * </ol>
   *
   * <p>Pekko runs those in exactly that order, which is what makes the guarantee structural rather
   * than a comment. If the process dies between steps 1 and 3 the operator sees a failed request
   * and retries; if it dies after step 1 the decision is already durable and recovery applies it.
   * The one thing that cannot happen is a 200 for a decision nobody wrote down.
   */
  private Effect<TurnState> onAnswerApproval(TurnState state, AnswerApproval answer) {
    if (!(state instanceof TurnState.WorkingTools working)) {
      return Effect()
          .none()
          .thenReply(answer.replyTo(), s -> new Ack(false, "no round is waiting on this agent"));
    }
    var call = working.call(answer.callId());
    if (call.isEmpty()) {
      return Effect()
          .none()
          .thenReply(answer.replyTo(), s -> new Ack(false, "no such call: " + answer.callId()));
    }
    if (call.get().decided() || call.get().settled()) {
      // Idempotent: a double-click, or a retry after a timeout the operator did not see land.
      return Effect().none().thenReply(answer.replyTo(), s -> new Ack(true, "already answered"));
    }
    var decided =
        call.get()
            .decidedBy(
                new ToolCallRecord.Decision(
                    answer.approved(), answer.by(), answer.note(), deps.clock().instant()));
    var next = working.replace(decided);
    return Effect()
        .persist(next)
        .thenRun(
            () -> {
              var child = callActors.get(answer.callId());
              if (child != null) {
                child.tell(new ToolCallActor.Answer(answer.approved(), answer.by(), answer.note()));
              } else {
                // Nothing in memory (a restart since the park). The persisted decision is enough:
                // spawn the call's actor and it reads the answer out of its own record.
                spawnMissing(next, Map.of());
              }
            })
        .thenReply(answer.replyTo(), s -> new Ack(true, answer.approved() ? "approved" : "denied"));
  }

  private Effect<TurnState> onToolCallSettled(TurnState state, ToolCallSettled settled) {
    if (!(state instanceof TurnState.WorkingTools working)) {
      return Effect().none();
    }
    var call = working.call(settled.callId());
    if (call.isEmpty() || call.get().settled()) {
      return Effect().none(); // the at-least-once tail: a duplicate outcome
    }
    callActors.remove(settled.callId());
    var updated = working.replace(call.get().settledWith(settled.outcome()));
    if (!updated.allSettled()) {
      return Effect().persist(updated);
    }
    var next = new TurnState.CallingModel(updated.transcriptWithResults());
    return Effect().persist(next).thenRun(() -> askModel(next, settled.trace()));
  }

  // ------------------------------------------------------------------------------------------
  // Effects — all AFTER the state above has been durably written
  // ------------------------------------------------------------------------------------------

  /** A plain fire-and-forget. The desk owns every scrap of the work-pulling protocol. */
  private void askModel(TurnState.CallingModel state, Map<String, String> trace) {
    deps.modelDesk().tell(new ModelDesk.CallModel(state.transcript(), context.getSelf(), trace));
  }

  /**
   * Spawn an actor for every call that has no outcome and no live child. Idempotent by
   * construction, which is exactly why the same method serves the normal path, the
   * answered-after-a-restart path, and recovery.
   */
  private void spawnMissing(TurnState.WorkingTools state, Map<String, String> trace) {
    for (ToolCallRecord call : state.unsettled()) {
      callActors.computeIfAbsent(
          call.id(),
          id ->
              context.spawn(
                  ToolCallActor.create(
                      call,
                      context.getSelf(),
                      deps.tools(),
                      deps.approvalTerm(),
                      trace,
                      deps.clock()),
                  ToolCallActor.nameFor(id)));
    }
  }

  // ------------------------------------------------------------------------------------------
  // Rehydration
  // ------------------------------------------------------------------------------------------

  @Override
  public SignalHandler<TurnState> signalHandler() {
    return newSignalHandlerBuilder()
        .onSignal(RecoveryCompleted.instance(), this::resume)
        .onSignal(
            PostStop.instance(),
            state -> context.getLog().info("[watchman] {} stopped while {}", agentId, name(state)))
        .build();
  }

  /**
   * What a rehydrated round still owes. The whole re-fire rule, and the reason {@link
   * ApprovalActor} can re-arm a deadline it never persisted: the ask time is in the record, so the
   * term is recomputed rather than restarted.
   *
   * <p>A round resumed here is on a NEW trace, linked to the one that parked it — see {@link
   * Traces#inLinkedSpan}. A round that waited three days is not one span.
   */
  private void resume(TurnState state) {
    context.getLog().info("[watchman] {} rehydrated while {}", agentId, name(state));
    switch (state) {
      case TurnState.Idle ignored -> {
        // nothing in flight
      }
      case TurnState.CallingModel calling -> askModel(calling, Map.of());
      case TurnState.WorkingTools working -> spawnMissing(working, Map.of());
    }
  }

  private static String name(TurnState state) {
    return switch (state) {
      case TurnState.Idle ignored -> "idle";
      case TurnState.CallingModel ignored -> "calling the model";
      case TurnState.WorkingTools ignored -> "working tools";
    };
  }
}
