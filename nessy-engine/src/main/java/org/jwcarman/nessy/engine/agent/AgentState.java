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
package org.jwcarman.nessy.engine.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.ObservationCoalescer;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.engine.backlog.Backlog;
import org.jwcarman.nessy.engine.backlog.Pull;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * Where an agent is, and nothing else.
 *
 * <p>No history. The story lives in its own table, one row per message, and the fold says what to
 * append by returning it -- so this document stays the size of a phase rather than growing with
 * every conversation it has ever had.
 *
 * <p><b>Only a working agent has a backlog.</b> An observation arriving at {@link Idle} is acted on
 * at once, so it never queues; one arriving mid-turn has nowhere to go but the queue. That makes an
 * idle agent with work waiting unrepresentable rather than merely unlikely -- if something is
 * waiting, the agent is not idle, and the type says so.
 *
 * <p><b>{@code <O>} stops at the renderer.</b> The backlog holds the caller's own observations,
 * unrendered. Everything downstream -- messages, effects, the request, the result -- is plain,
 * which is why only this document needs a codec built for the caller's type and every stored
 * message can share one.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AgentState.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = AgentState.Inferring.class, name = "inferring"),
  @JsonSubTypes.Type(value = AgentState.AwaitingActions.class, name = "awaiting-actions"),
  @JsonSubTypes.Type(value = AgentState.Terminated.class, name = "terminated")
})
public sealed interface AgentState<O> {

  /**
   * Something to work on arrived. Whether it is worked on now is this fold's business.
   *
   * <p>Two methods rather than one taking a sealed event type, because the two inputs are not the
   * same kind of thing and pretending otherwise cost us a wildcard. An observation is the caller's,
   * generic in {@code O}; an outcome is the engine's own vocabulary and knows nothing about {@code
   * O}. One sealed input forced every arm to carry a type parameter it had no use for -- {@code
   * InferenceAnswered<O>} named a type it did not contain.
   *
   * @param arrivedAt when it arrived, passed in rather than read, so the fold has no clock
   */
  Decision<O> observe(O observation, Instant arrivedAt, ObservationCoalescer<O> coalescer);

  /** An effect this agent owed was performed, and this is what came of it. */
  Decision<O> outcome(EffectOutcome outcome);

  /**
   * Ends this agent. Nothing is recorded: terminating is an operational fact about the agent, not
   * something that happened in its conversation, and the model has no use for it.
   *
   * <p>Immediate only when nothing is outstanding. An agent mid-turn owes an outcome on an effect
   * row that already exists, so it seals its intake and ends when that turn does -- which is the
   * one moment it asks the backlog for more and is told there will never be any.
   */
  Decision<O> terminate();

  /**
   * The seq of the last message this agent recorded, or zero if it has recorded none.
   *
   * <p>Here rather than looked up, because the fold has to number what it records and asking the
   * store would make the fold depend on a query. The state and the story are written in one
   * transaction under the agent's row lock, and {@code (agent_type, agent_id, seq)} is the primary
   * key -- so if this ever disagreed with what is stored, the insert fails rather than quietly
   * writing a second message at an occupied position.
   */
  Seq lastSeq();

  /**
   * Nothing in flight and nothing waiting -- the second follows from the first.
   *
   * <p>Carries {@code lastSeq} and no turn, for the same reason it carries no backlog: there is no
   * turn to carry. The counter has to survive between turns or the next one would start numbering
   * from the beginning and collide with the story already written.
   */
  record Idle<O>(Seq lastSeq) implements AgentState<O> {

    @Override
    public Decision<O> terminate() {
      // Nothing is outstanding and nothing is waiting -- an idle agent has no backlog to
      // seal and no turn to finish, so there is nothing to wait for.
      return Decision.stay(new Terminated<O>(lastSeq));
    }

    @Override
    public Decision<O> observe(
        O observation, Instant arrivedAt, ObservationCoalescer<O> coalescer) {
      return begin(observation, lastSeq, Backlog.empty(), List.of());
    }

    /**
     * No call is outstanding, so this is a redelivery of one whose turn already closed -- the
     * effect row outlived its own fold. Writing anything would put a second answer in the story,
     * which is exactly what at-least-once delivery would otherwise cost.
     */
    @Override
    public Decision<O> outcome(EffectOutcome outcome) {
      return Decision.ignore();
    }

    @Override
    public Seq lastSeq() {
      return lastSeq;
    }
  }

  /**
   * A model call is outstanding. Whatever arrives meanwhile waits.
   *
   * <p>{@code turn} is the seq of the observation that opened it, so a turn still needs no
   * identifier of its own and the state can name the turn it is working on -- which is what lets
   * the fold refer to a stored row without ever seeing one.
   */
  record Inferring<O>(Seq lastSeq, TurnId turn, Backlog<O> backlog) implements AgentState<O> {

    @Override
    public Decision<O> terminate() {
      Backlog<O> sealed = backlog.seal();
      // Sealing twice changes nothing, and a fold that wrote a version bump for it would be
      // recording that something happened when nothing did.
      return sealed.equals(backlog)
          ? Decision.ignore()
          : Decision.stay(new Inferring<>(lastSeq, turn, sealed));
    }

    @Override
    public Decision<O> observe(
        O observation, Instant arrivedAt, ObservationCoalescer<O> coalescer) {
      Backlog<O> next = backlog.accept(new BacklogItem<>(observation, arrivedAt), coalescer);
      // A coalescer that dropped the arrival, or a sealed backlog that refused it, changed
      // nothing -- and a fold that wrote a version bump for that would be recording that
      // something happened when nothing did.
      return next.equals(backlog)
          ? Decision.ignore()
          : Decision.stay(new Inferring<>(lastSeq, turn, next));
    }

    @Override
    public Decision<O> outcome(EffectOutcome outcome) {
      return switch (outcome) {
        case EffectOutcome.InferenceAnswered(var blocks) ->
            endTurn(new HistoryEntry.InferenceAnswered(lastSeq.next(), turn, blocks));
        // Recorded, but never as an answer -- that would tell the model it once said
        // something it never said. A turn is closed by exactly one of these two, so the
        // story never leaves a question standing with nothing after it.
        case EffectOutcome.InferenceFailed _ ->
            endTurn(new HistoryEntry.InferenceFailed(lastSeq.next(), turn));
        // A refusal is not "try again later" -- the same request will be declined every
        // time, and while it stays in what we send, so will everything after it. Closing
        // the turn is not enough: the observation has to stop being sent, or the agent is
        // refused forever. It is set aside, never deleted.
        case EffectOutcome.InferenceRefused _ ->
            endTurn(new HistoryEntry.InferenceRefused(lastSeq.next(), turn));
        // The one answer that does not end the turn. The calls are written down as one
        // entry, and one effect is emitted per call -- so the obligations become durable
        // in the same transaction that records taking them on, and a crash between the
        // two is not a thing that can happen.
        case EffectOutcome.InferenceRequestedActions(var blocks) -> requestActions(blocks);
        // No call is outstanding, so this is a redelivery of a tool whose turn has
        // already moved on. Writing it would put a second result in the story for a call
        // that already has one, which no provider accepts.
        case EffectOutcome.ToolSucceeded _,
            EffectOutcome.ToolFailed _,
            EffectOutcome.ToolDenied _,
            EffectOutcome.ToolApproved _ ->
            Decision.ignore();
      };
    }

    private Decision<O> requestActions(List<Block.ActionRequestContent> blocks) {
      Seq seq = lastSeq.next();
      HistoryEntry.InferenceRequestedActions request =
          new HistoryEntry.InferenceRequestedActions(seq, turn, blocks);
      List<Block.ToolCall> calls = request.calls();
      // Insertion-ordered, so the order the model asked in is the order the work is queued
      // in. Nothing downstream depends on that -- results are matched by id -- but a map
      // that reordered would make the same story replay differently on different days.
      Map<CallId, Outstanding> outstanding = new LinkedHashMap<>();
      for (Block.ToolCall call : calls) {
        outstanding.put(call.id(), Outstanding.awaitingApproval(call.name()));
      }
      // Nothing is dispatched to a tool here. Every call is asked about first, including
      // the ones nothing is gating, so there is exactly one path into a running tool.
      return new Decision.Advance<>(
          new AwaitingActions<>(seq, turn, seq, backlog, outstanding),
          List.of(request),
          calls.stream()
              .<AgentEffect>map(call -> new AgentEffect.Approve(seq, call.id(), call.name()))
              .toList());
    }

    /** Closes the turn and takes the next observation if one has been waiting. */
    /**
     * @param closer how this turn ended: what the model said, or that it could not say it
     */
    private Decision<O> endTurn(HistoryEntry closer) {
      List<HistoryEntry> closing = List.of(closer);
      return switch (backlog.next()) {
        case Pull.Item<O>(var item, var remainder) ->
            begin(item.observation(), closer.seq(), remainder, closing);
        case Pull.Empty<O> _ ->
            new Decision.Advance<>(new Idle<>(closer.seq()), closing, List.of());
        // Not Idle: the pill says there will never be anything more. Ending anywhere an
        // observation could be accepted again would make termination undoable by whoever
        // sent the next one.
        case Pull.Pill<O> _ ->
            new Decision.Advance<>(new Terminated<>(closer.seq()), closing, List.of());
      };
    }

    @Override
    public Seq lastSeq() {
      return lastSeq;
    }
  }

  /**
   * The model asked for work and the engine took it on. The turn is still open.
   *
   * <p>Sits beside {@link Inferring} rather than inside it because the two wait on different things
   * and answer differently to the same arrivals: an inference is one obligation that ends a turn,
   * and this is many obligations that end a round. What they share -- queueing whatever arrives,
   * sealing on terminate -- they share by saying the same thing twice, which is cheaper than a
   * shared supertype that would have to be sealed over both.
   *
   * <p><b>{@code outstanding} is the obligation, made explicit.</b> Every call the model made is in
   * it, and each leaves exactly once, when its outcome is folded. The turn cannot advance while it
   * has members, and it cannot shrink twice for the same call -- which is what makes a redelivered
   * outcome harmless rather than a second result the provider will reject.
   *
   * @param lastSeq the seq of the last entry recorded, which advances as results are written
   * @param turn the turn all of this belongs to, unchanged since the observation opened it
   */
  record AwaitingActions<O>(
      Seq lastSeq,
      TurnId turn,
      Seq requestSeq,
      Backlog<O> backlog,
      Map<CallId, Outstanding> outstanding)
      implements AgentState<O> {

    public AwaitingActions {
      if (outstanding.isEmpty()) {
        // With nothing left to wait for there is nothing to come and move this on, so the
        // agent would sit here forever holding a turn open. The fold goes back to
        // inferring the moment the last call is discharged, and never lands here empty.
        throw new IllegalArgumentException("an agent awaiting nothing is not awaiting");
      }
      outstanding = Map.copyOf(outstanding);
    }

    @Override
    public Decision<O> terminate() {
      Backlog<O> sealed = backlog.seal();
      // Same as mid-inference: the calls already have rows and are owed outcomes, so the
      // agent stops accepting and ends when the turn does. The seal rides back through
      // inferring when the last result lands.
      return sealed.equals(backlog)
          ? Decision.ignore()
          : Decision.stay(new AwaitingActions<>(lastSeq, turn, requestSeq, sealed, outstanding));
    }

    @Override
    public Decision<O> observe(
        O observation, Instant arrivedAt, ObservationCoalescer<O> coalescer) {
      Backlog<O> next = backlog.accept(new BacklogItem<>(observation, arrivedAt), coalescer);
      return next.equals(backlog)
          ? Decision.ignore()
          : Decision.stay(new AwaitingActions<>(lastSeq, turn, requestSeq, next, outstanding));
    }

    @Override
    public Decision<O> outcome(EffectOutcome outcome) {
      return switch (outcome) {
        case EffectOutcome.ToolApproved(CallId callId, var reference) ->
            dispatch(callId, reference);
        case EffectOutcome.ToolSucceeded(CallId callId, var blocks) ->
            discharge(callId, seq -> new HistoryEntry.ToolSucceeded(seq, turn, callId, blocks));
        case EffectOutcome.ToolFailed(CallId callId, String message) ->
            discharge(callId, seq -> new HistoryEntry.ToolFailed(seq, turn, callId, message));
        case EffectOutcome.ToolDenied(CallId callId, String reason, var reference) ->
            discharge(
                callId, seq -> new HistoryEntry.ToolDenied(seq, turn, callId, reason, reference));
        // The inference that got us here is finished and its row is gone. Anything of
        // its shape arriving now is a redelivery, and recording it would close a turn
        // that still owes results -- leaving calls in the story with nothing answering
        // them, which is the one corruption that cannot be sent to any provider again.
        case EffectOutcome.InferenceAnswered _,
            EffectOutcome.InferenceFailed _,
            EffectOutcome.InferenceRefused _,
            EffectOutcome.InferenceRequestedActions _ ->
            Decision.ignore();
      };
    }

    /**
     * Permission was granted, so the call is written down and dispatched.
     *
     * <p><b>The grant is recorded even though no model will ever read it.</b> That is what makes
     * "every call that ran was approved" a property of the story rather than a promise of the code:
     * the entry and the call effect are written in one transaction, so there is no instant at which
     * a tool is dispatched with nothing saying it was allowed.
     *
     * <p>The phase moves in the same breath, and that is what makes a redelivered approval harmless
     * -- approving removes nothing from the outstanding set, so presence alone could not tell a
     * first approval from a second.
     */
    private Decision<O> dispatch(CallId callId, Optional<String> reference) {
      Outstanding call = outstanding.get(callId);
      if (call == null || call.phase() != Outstanding.Phase.AWAITING_APPROVAL) {
        // Either already discharged, already running, or never asked for. Dispatching
        // again would run a tool a second time, which for anything that touches the
        // world outside the agent is the one mistake with no way back.
        return Decision.ignore();
      }
      Seq seq = lastSeq.next();
      Map<CallId, Outstanding> next = new LinkedHashMap<>(outstanding);
      next.put(callId, call.running());
      return new Decision.Advance<>(
          new AwaitingActions<>(seq, turn, requestSeq, backlog, next),
          List.of(new HistoryEntry.ToolApproved(seq, turn, callId, reference)),
          List.of(new AgentEffect.CallTool(requestSeq, callId, call.toolName())));
    }

    /**
     * Writes one result down and takes its call off the list.
     *
     * <p>Accepted from either phase. A result while a call is still awaiting approval looks wrong
     * but is ordinary: failing to <em>ask</em> discharges the call too, and refusing it here would
     * leave the agent waiting on an approval nobody will ever give again.
     *
     * <p>When the list empties the model is asked again, in the same turn: it asked for the work in
     * order to answer, and now it can. A round ending is not a turn ending.
     */
    private Decision<O> discharge(CallId callId, Function<Seq, HistoryEntry> entry) {
      if (!outstanding.containsKey(callId)) {
        // Either a redelivery of a call already discharged, or a result for something
        // this turn never asked for. Both are writes that would make the conversation
        // unsendable, and neither is worth telling apart to refuse.
        return Decision.ignore();
      }
      Seq seq = lastSeq.next();
      List<HistoryEntry> recorded = List.of(entry.apply(seq));
      Map<CallId, Outstanding> remaining = new LinkedHashMap<>(outstanding);
      remaining.remove(callId);
      return remaining.isEmpty()
          ? new Decision.Advance<>(
              new Inferring<>(seq, turn, backlog), recorded, List.of(new AgentEffect.Infer()))
          : new Decision.Advance<>(
              new AwaitingActions<>(seq, turn, requestSeq, backlog, remaining),
              recorded,
              List.of());
    }

    /** How many calls are at one point in their lifecycle. For logs, and for tests. */
    public int countOf(Outstanding.Phase phase) {
      return (int) outstanding.values().stream().filter(call -> call.phase() == phase).count();
    }

    @Override
    public Seq lastSeq() {
      return lastSeq;
    }
  }

  /**
   * Opens a turn: the observation becomes the first message of it, and the model is called.
   *
   * <p>The opening's seq is also the turn's, so a turn needs no identifier of its own. Numbering
   * runs on from {@code lastSeq}, which may be the seq of an entry this same fold is about to
   * record -- a turn closing and the next opening is one fold and two consecutive numbers.
   *
   * <p>Nothing is rendered here. The observation travels as itself and the store renders it, which
   * is what keeps {@code <O>} out of the fold's output and the renderer out of the state machine.
   */
  private static <O> Decision<O> begin(
      O observation, Seq lastSeq, Backlog<O> backlog, List<HistoryEntry> before) {
    Seq seq = lastSeq.next();
    return new Decision.Advance<>(
        new Inferring<>(seq, seq.opensTurn(), backlog),
        before,
        new Decision.Opening<>(seq, observation),
        List.of(new AgentEffect.Infer()));
  }

  /**
   * Done. Accepts nothing, owes nothing, and never moves again.
   *
   * <p>Reached two ways, and the difference is only in how long it took: an idle agent goes
   * straight here, and a working one goes here when its outstanding turn ends. Either way nothing
   * was written to the story on the way -- what ended is the agent, not the conversation.
   *
   * <p>Keeps {@code lastSeq} so the story it leaves behind is still readable and still correctly
   * numbered, even though nothing will ever be added to it.
   */
  record Terminated<O>(Seq lastSeq) implements AgentState<O> {

    @Override
    public Decision<O> observe(
        O observation, Instant arrivedAt, ObservationCoalescer<O> coalescer) {
      // Refused. There is no backlog to put it in and no turn that will ever take it.
      return Decision.ignore();
    }

    @Override
    public Decision<O> outcome(EffectOutcome outcome) {
      // A redelivery of the effect whose turn ended by terminating this agent. Its row
      // outlived its own fold; writing anything now would add to a story that is closed.
      return Decision.ignore();
    }

    @Override
    public Decision<O> terminate() {
      return Decision.ignore();
    }

    @Override
    public Seq lastSeq() {
      return lastSeq;
    }
  }
}
