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

  /**
   * From the cron, or from anyone with something to say.
   *
   * @param coalesceKey observations sharing a key SUPERSEDE one another while a round is busy — a
   *     cron tick is only ever "do your rounds now", so twenty queued ticks are one tick. The
   *     sender decides, because only the sender knows whether its message replaces or accumulates:
   *     a person's message must pass {@code null} and can never be merged with anything.
   */
  public record Observe(String text, String coalesceKey, Map<String, String> trace)
      implements Command {}

  /** From a model worker. */
  public record ModelReplied(ModelReply reply, Map<String, String> trace) implements Command {}

  /**
   * From one of this agent's own tool-call children. Carries no outcome: the result is already a
   * transcript turn, written by whoever produced it. The agent only records that the call is done.
   */
  public record ToolCallSettled(String callId, Map<String, String> trace) implements Command {}

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
      Transcript transcript,
      java.util.concurrent.Executor blocking,
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
    return new TurnState.Idle();
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

  /**
   * <b>An observation arriving mid-round is REFUSED, loudly, and that is the honest state of
   * this.</b>
   *
   * <p>It used to be dropped in silence — {@code Effect().none()} with no log line — which is data
   * loss dressed as a decision. It is now at least visible. It is still a drop.
   *
   * <p>I tried the obvious fix and backed it out. An in-memory queue on this actor, with the
   * observations coalesced by {@link Observe#coalesceKey} and drained when the round reaches Idle,
   * queues and coalesces correctly (twenty ticks really do collapse to one) — and then starts the
   * NEXT round about 200 ms after the first one PARKS, while its approval is still outstanding. I
   * could not isolate that interaction in the time available, and a watchman that begins a second
   * round while a human is still looking at the first proposal is worse than one that drops a cron
   * tick. So it is not here.
   *
   * <p>What the attempt did make clear is that the in-memory version was the wrong shape anyway.
   * "Is this round finished?" is a question about PERSISTED state, and the answer has to be read
   * from it rather than inferred from a callback; and an observation a caller was told we accepted
   * has to be written down before it is acknowledged — the rule the approvals page already follows.
   * A durable inbox is the real answer, not a field on this actor.
   */
  private Effect<TurnState> onObserve(TurnState state, Observe observe) {
    if (!(state instanceof TurnState.Idle)) {
      context
          .getLog()
          .warn(
              "[watchman] REFUSED an observation: a round is already in flight. It is not queued"
                  + " and it is not coming back. text={}, key={}",
              observe.text(),
              observe.coalesceKey());
      return Effect().none();
    }
    // The user's turn is already in the transcript -- whoever sent this wrote it first. The agent
    // only records that a round has started.
    return Effect().persist(new TurnState.CallingModel()).thenRun(() -> askModel(observe.trace()));
  }

  private Effect<TurnState> onModelReplied(TurnState state, ModelReplied replied) {
    if (!(state instanceof TurnState.CallingModel)) {
      return Effect().none();
    }
    // Every arm's transcript turn was appended by the model worker before this message was sent.
    return switch (replied.reply()) {
      case ModelReply.Said ignored -> Effect().persist(new TurnState.Idle());
      case ModelReply.Failed ignored -> Effect().persist(new TurnState.Idle());
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
        var next = new TurnState.WorkingTools(calls);
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
    var updated = working.replace(call.get().settle());
    if (!updated.allSettled()) {
      return Effect().persist(updated);
    }
    return Effect().persist(new TurnState.CallingModel()).thenRun(() -> askModel(settled.trace()));
  }

  // ------------------------------------------------------------------------------------------
  // Effects — all AFTER the state above has been durably written
  // ------------------------------------------------------------------------------------------

  /**
   * A plain fire-and-forget. The desk owns every scrap of the work-pulling protocol, and the WORKER
   * recalls the transcript on its own thread — the agent never assembles model context, and never
   * holds one.
   */
  private void askModel(Map<String, String> trace) {
    deps.modelDesk()
        .tell(
            new ModelDesk.CallModel(
                agentId, new TurnState.CallingModel(), context.getSelf(), trace));
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
                      agentId,
                      call,
                      context.getSelf(),
                      deps.tools(),
                      deps.approvalTerm(),
                      trace,
                      deps.clock(),
                      deps.transcript(),
                      deps.blocking()),
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
      case TurnState.CallingModel ignored -> askModel(Map.of());
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
