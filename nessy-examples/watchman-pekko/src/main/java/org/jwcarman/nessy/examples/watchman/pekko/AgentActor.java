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

import io.micrometer.tracing.Span;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.jwcarman.nessy.api.Identifiers;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.Remembrance;

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
public final class AgentActor extends DurableStateBehavior<AgentActor.NessyMessage, AgentState> {

  /** How a runtime asks for this agent to be let go. */
  @FunctionalInterface
  public interface StopRequest {
    void requestStop(String agentId, ActorRef<NessyMessage> self);
  }

  /**
   * Every message this actor accepts, and the trace context that reached it.
   *
   * <p><b>Headers are on the interface, not on an envelope.</b> The alternative was a wrapper
   * record — {@code NessyMessage(Command payload, Map<String,String> headers)} — which keeps
   * tracing out of each record's signature. It was rejected because it makes carrying context
   * OPTIONAL at every send site, and a send that forgets produces an orphan span rather than a
   * compile error: a failure that is silent, rare, and only noticed when someone needs the trace.
   * Here the compiler refuses to let a new message type exist without saying how it is traced.
   *
   * <p>The price is that {@code headers} appears in fifteen record signatures. That is the trade,
   * taken deliberately, and it is cheaper than one silently broken trace.
   *
   * <p>Non-generic on purpose: {@code EntityTypeKey.create} and {@code ServiceKey.create} need
   * class literals, and a generic type would be raw there — an unchecked warning this repo does not
   * permit anyone to suppress. Each actor nests its own, which loses nothing because each actor
   * already owns its own protocol.
   */
  public sealed interface NessyMessage {

    /** W3C {@code traceparent} and friends. Empty when the sender had no context. */
    Map<String, String> headers();
  }

  /**
   * From the cron, or from anyone with something to say.
   *
   * @param coalesceKey observations sharing a key SUPERSEDE one another while a round is busy — a
   *     cron tick is only ever "do your rounds now", so twenty queued ticks are one tick. The
   *     sender decides, because only the sender knows whether its message replaces or accumulates:
   *     a person's message must pass {@code null} and can never be merged with anything.
   */
  public record Observe(String text, String coalesceKey, Map<String, String> headers)
      implements NessyMessage {}

  /** From a model worker. */
  public record ModelReplied(ModelReply reply, Map<String, String> headers)
      implements NessyMessage {}

  /**
   * From one of this agent's own tool-call children. Carries no outcome: the result is already a
   * transcript turn, written by whoever produced it. The agent only records that the call is done.
   */
  public record ToolCallSettled(String callId, Map<String, String> headers)
      implements NessyMessage {}

  /**
   * From the approvals page. Carries a {@code replyTo} because the HTTP handler must not return 200
   * until this has been written down — see {@link #onAnswerApproval}.
   */
  public record AnswerApproval(
      String callId,
      boolean approved,
      String by,
      String note,
      ActorRef<Ack> replyTo,
      Map<String, String> headers)
      implements NessyMessage {}

  /** What the page is told once the decision is durable. */
  public record Ack(boolean accepted, String detail) {}

  /** From the page and the tests: what does this agent look like right now? */
  public record Inspect(ActorRef<AgentState> replyTo, Map<String, String> headers)
      implements NessyMessage {}

  /** From the startup sweep: exists only to bring the actor into memory so recovery can run. */
  public record Wake(Map<String, String> headers) implements NessyMessage {}

  /** From the world: let go of memory. */
  public record Rest(Map<String, String> headers) implements NessyMessage {}

  /** From the runtime, in response to {@link Rest}. The only message that ends the actor. */
  public record Stop(Map<String, String> headers) implements NessyMessage {}

  public static final Stop STOP = new Stop(Map.of());

  private final ActorContext<NessyMessage> context;
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
      Memories memories,
      Backlogs<String> backlogs,
      java.util.concurrent.Executor blocking,
      Traces traces,
      Clock clock,
      Duration approvalTerm) {}

  public static Behavior<NessyMessage> create(
      String agentId, Dependencies deps, StopRequest stopRequest) {
    return Behaviors.setup(context -> new AgentActor(context, agentId, deps, stopRequest));
  }

  private final StopRequest stopRequest;

  private AgentActor(
      ActorContext<NessyMessage> context,
      String agentId,
      Dependencies deps,
      StopRequest stopRequest) {
    super(PersistenceId.of("Watchman", agentId));
    this.context = context;
    this.agentId = agentId;
    this.deps = deps;
    this.stopRequest = stopRequest;
  }

  @Override
  public AgentState emptyState() {
    return AgentState.idle();
  }

  @Override
  public CommandHandler<NessyMessage, AgentState> commandHandler() {
    return (state, message) ->
        deps.traces()
            .inSpan(
                spanName(message),
                Span.Kind.CONSUMER,
                message.headers(),
                () -> {
                  describe(state, message);
                  return handle(state, message);
                });
  }

  /**
   * What this actor knows about itself, written onto the receive span.
   *
   * <p>These are the attributes that make a trace answer operational questions instead of merely
   * showing that something happened:
   *
   * <ul>
   *   <li><b>{@code nessy.actor.path}</b> — the full actor path. Under clustering this names the
   *       node, which is the difference between "a turn stalled" and "a turn stalled on the box
   *       that went away". It is also the only attribute here that changes when an entity moves.
   *   <li><b>{@code nessy.agent.id}</b> — the partition key for everything: mailbox ordering,
   *       persistence, and the one-turn-at-a-time rule. Filtering a trace search by it gives one
   *       agent's whole history.
   *   <li><b>{@code nessy.turn.phase}</b> — the phase the message ARRIVED at, which is what decides
   *       whether it is admitted or dropped. A refused observation and an accepted one look
   *       identical without it.
   *   <li><b>{@code nessy.persistence.id}</b> — what to look up in the durable-state table.
   * </ul>
   *
   * <p>All four are low cardinality per agent and bounded by the sealed protocol, so none of them
   * is the attribute that blows up a backend's index.
   */
  private void describe(AgentState state, NessyMessage message) {
    Traces traces = deps.traces();
    traces.tag("messaging.system", "pekko");
    traces.tag("nessy.agent.id", agentId);
    traces.tag("nessy.actor.path", context.getSelf().path().toString());
    traces.tag("nessy.message.type", message.getClass().getSimpleName());
    traces.tag("nessy.turn.phase", state.phase().getClass().getSimpleName());
    traces.tag("nessy.persistence.id", persistenceId().id());

    // The node. Locally this renders "pekko://watchman"; under clustering it becomes
    // "pekko://watchman@host:port", so the SAME attribute answers "which box" the day we go
    // multi-node, without anyone having to remember to add it then.
    traces.tag("nessy.node.address", context.getSystem().address().toString());

    // Live children against persisted work: the DIFFERENCE is the diagnostic. Two unsettled calls
    // and zero children means the process restarted and the tool actors have not been respawned
    // yet -- a state that is invisible from either number alone.
    traces.tag("nessy.actor.children", String.valueOf(context.getChildren().size()));
    if (state.phase() instanceof Phase.WorkingTools working) {
      traces.tag("nessy.tools.unsettled", String.valueOf(working.unsettled().size()));
    }
  }

  /**
   * The span every message gets, named for the actor and the message.
   *
   * <p>CONSUMER because the mailbox is a queue: paired with the PRODUCER span at the send, the gap
   * between them is queue latency, which is exactly the thing an actor system makes easy to have
   * and hard to see. The message type is safe in a span name because the protocol is a SEALED
   * interface — the cardinality is bounded by the compiler, not by hope.
   */
  private static String spanName(NessyMessage message) {
    return "agent receive " + message.getClass().getSimpleName();
  }

  /**
   * Runs INSIDE the receive span, so {@code deps.traces().capture()} here is this actor's own
   * context.
   *
   * <p><b>{@code here} is captured eagerly and on purpose.</b> A {@code thenRun} block runs after
   * persistence commits, potentially on another thread, and always after this span's scope has
   * closed — so a {@code capture()} inside one would come back empty and silently orphan everything
   * downstream. Capturing at the top and closing over the result is what keeps a persisted effect's
   * sends attached to the message that caused them.
   */
  private Effect<AgentState> handle(AgentState state, NessyMessage message) {
    Map<String, String> here = deps.traces().capture();
    return switch (message) {
      case Inspect inspect -> Effect().none().thenRun(() -> inspect.replyTo().tell(state));
      case Wake ignored -> Effect().none();
      case Rest ignored ->
          Effect().none().thenRun(() -> stopRequest.requestStop(agentId, context.getSelf()));
      case Stop ignored -> Effect().none().thenStop();
      case Observe observe -> onObserve(state, observe, here);
      case ModelReplied replied -> onModelReplied(state, replied, here);
      case AnswerApproval answer -> onAnswerApproval(state, answer, here);
      case ToolCallSettled settled -> onToolCallSettled(state, settled, here);
    };
  }

  /**
   * <b>An observation is durable the instant {@link Backlogs#ingest} returns — never refused, never
   * dropped.</b> Measured live on a one-minute cadence while parked on a single approval: 26 of 31
   * rounds refused under the version that came before this one. That was not an edge case, it was
   * the steady state of any agent that both runs continuously and asks a human anything.
   *
   * <p>Idle means nobody else is going to drain this, so a turn starts right here. A round already
   * in flight drains it when that round finishes — see {@link #onModelReplied} and {@link
   * #startTurnIfWork}.
   */
  private Effect<AgentState> onObserve(
      AgentState state, Observe observe, Map<String, String> here) {
    deps.backlogs().ingest(agentId, observe.text(), deps.clock().instant());
    if (state.phase() instanceof Phase.Idle) {
      return startTurnIfWork(state, here);
    }
    return Effect().none();
  }

  private Effect<AgentState> onModelReplied(
      AgentState state, ModelReplied replied, Map<String, String> here) {
    if (!(state.phase() instanceof Phase.CallingModel)) {
      return Effect().none();
    }
    // Every arm's assistant turn was remembered by the model worker before this message was sent.
    return switch (replied.reply()) {
      case ModelReply.Said ignored -> startTurnIfWork(state, here);
      case ModelReply.Failed ignored -> startTurnIfWork(state, here);
      case ModelReply.AskedForTools(var ignoredMessage, var requests, var ignoredUsage) -> {
        var now = deps.clock().instant();
        var calls =
            requests.stream()
                .map(
                    request ->
                        ToolCallRecord.asked(
                            request.id(),
                            request.name(),
                            request.arguments().toString(),
                            WatchmanTools.action(request.name(), request.arguments().toString()),
                            now))
                .toList();
        var nextPhase = new Phase.WorkingTools(calls);
        var next = state.withPhase(nextPhase);
        yield Effect().persist(next).thenRun(() -> spawnMissing(nextPhase, here));
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
  private Effect<AgentState> onAnswerApproval(
      AgentState state, AnswerApproval answer, Map<String, String> here) {
    if (!(state.phase() instanceof Phase.WorkingTools working)) {
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
    var nextPhase = working.replace(decided);
    var next = state.withPhase(nextPhase);
    return Effect()
        .persist(next)
        .thenRun(
            () -> {
              var child = callActors.get(answer.callId());
              if (child != null) {
                child.tell(
                    new ToolCallActor.Answer(answer.approved(), answer.by(), answer.note(), here));
              } else {
                // Nothing in memory (a restart since the park). The persisted decision is enough:
                // spawn the call's actor and it reads the answer out of its own record.
                spawnMissing(nextPhase, here);
              }
            })
        .thenReply(answer.replyTo(), s -> new Ack(true, answer.approved() ? "approved" : "denied"));
  }

  private Effect<AgentState> onToolCallSettled(
      AgentState state, ToolCallSettled settled, Map<String, String> here) {
    if (!(state.phase() instanceof Phase.WorkingTools working)) {
      return Effect().none();
    }
    var call = working.call(settled.callId());
    if (call.isEmpty() || call.get().settled()) {
      return Effect().none(); // the at-least-once tail: a duplicate outcome
    }
    callActors.remove(settled.callId());
    var updated = working.replace(call.get().settle());
    if (!updated.allSettled()) {
      return Effect().persist(state.withPhase(updated));
    }
    var next = state.withPhase(new Phase.CallingModel());
    return Effect().persist(next).thenRun(() -> askModel(next, here));
  }

  /**
   * Turn start, and the only place a queued observation becomes a transcript entry.
   *
   * <p>ONE observation per turn, never the whole backlog. Draining everything into a single user
   * message would silently override the caller's {@link Coalescer} policy: a vocabulary whose
   * coalescer returns no key is saying "these must never merge", and merging them here would do
   * exactly that without the caller's own {@code merge} function. Coalescing on write, inside
   * {@link Backlogs#ingest}, is the only place observations become one — see {@link Backlog}.
   */
  private Effect<AgentState> startTurnIfWork(AgentState state, Map<String, String> here) {
    Optional<Backlogs.Taken<String>> taken = deps.backlogs().next(agentId);
    if (taken.isEmpty()) {
      return Effect().persist(state.finishedTurn());
    }
    Backlogs.Taken<String> observation = taken.get();
    // Transcript FIRST, then the state that references it (principle 1.1): a crash here leaves an
    // orphan entry, and the deterministic key below makes re-taking it a no-op.
    deps.memories().forAgent(agentId).remember(userMessage(observation));
    deps.backlogs().taken(agentId, observation.entryId());
    AgentState next = state.startingTurn(Identifiers.next()).withPhase(new Phase.CallingModel());
    return Effect().persist(next).thenRun(() -> askModel(next, here));
  }

  /** Key DERIVED from the entry id, never minted: re-taking after a crash must be free. */
  static Remembrance.UserMessage userMessage(Backlogs.Taken<String> taken) {
    return new Remembrance.UserMessage(
        "obs:" + taken.entryId(), Message.user(List.of(new TextBlock(taken.observation()))));
  }

  // ------------------------------------------------------------------------------------------
  // Effects — all AFTER the state above has been durably written
  // ------------------------------------------------------------------------------------------

  /**
   * A plain fire-and-forget. The desk owns every scrap of the work-pulling protocol, and the WORKER
   * recalls the transcript on its own thread — the agent never assembles model context, and never
   * holds one.
   */
  private void askModel(AgentState state, Map<String, String> trace) {
    deps.modelDesk().tell(new ModelDesk.CallModel(agentId, state, context.getSelf(), trace));
  }

  /**
   * Spawn an actor for every call that has no outcome and no live child. Idempotent by
   * construction, which is exactly why the same method serves the normal path, the
   * answered-after-a-restart path, and recovery.
   */
  private void spawnMissing(Phase.WorkingTools state, Map<String, String> trace) {
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
                      deps.memories(),
                      deps.blocking()),
                  ToolCallActor.nameFor(id)));
    }
  }

  // ------------------------------------------------------------------------------------------
  // Rehydration
  // ------------------------------------------------------------------------------------------

  @Override
  public SignalHandler<AgentState> signalHandler() {
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
  private void resume(AgentState state) {
    context.getLog().info("[watchman] {} rehydrated while {}", agentId, name(state));
    switch (state.phase()) {
      case Phase.Idle ignored -> {
        // nothing in flight
      }
      case Phase.CallingModel ignored -> askModel(state, Map.of());
      case Phase.WorkingTools working -> spawnMissing(working, Map.of());
    }
  }

  private static String name(AgentState state) {
    return switch (state.phase()) {
      case Phase.Idle ignored -> "idle";
      case Phase.CallingModel ignored -> "calling the model";
      case Phase.WorkingTools ignored -> "working tools";
    };
  }
}
