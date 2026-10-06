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
package org.jwcarman.nessy.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * Something an agent did, announced to whoever is watching.
 *
 * <p><b>An event is not an entry.</b> {@code HistoryEntry} is what is durable -- written in the
 * fold's transaction, re-read forever, and the thing a model is eventually shown. This is what is
 * <em>announced</em>: delivered once, to whoever happens to be listening, and gone. Losing one
 * costs a watcher a line; the fact it described is still in the story.
 *
 * <p>That difference is what makes the two vocabularies diverge rather than mirror each other.
 * Plenty here leaves no entry at all -- a person being asked, a token arriving mid-sentence -- and
 * plenty of entries are not worth announcing. Trying to derive one from the other would force both
 * to be shaped by the other's needs.
 *
 * <p><b>Two groups, and the difference is whether anything is stored.</b> A {@link Story} event is
 * the agent's record told as it commits, so by the time one is heard it is true, and it is told the
 * same way however many times the story is replayed. A {@link Live} signal is heard only as it
 * happens: a delta from a provider is true only of the attempt that is streaming -- a call that
 * fails and is retried narrates twice -- and a watcher should treat it as what is being said rather
 * than as what was said.
 *
 * <p><b>No timestamp and no agent.</b> A sink stamps events if it cares, rather than every delta
 * paying for a clock read; and identity is passed beside the event by {@link Narrator}, which is
 * what lets a provider narrate without ever being told which agent it is serving.
 *
 * <p><b>Typed on the wire.</b> An event is announced and forgotten by the engine, but a narrator
 * may journal it and a page may read it back later -- so, like {@link
 * org.jwcarman.nessy.api.block.Block}, an event names its kind in JSON. The names are the kinds in
 * kebab-case, and they are the event names such a narrator uses on the wire too.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Narration.TurnStarted.class, name = "turn-started"),
  @JsonSubTypes.Type(value = Narration.Thinking.class, name = "thinking"),
  @JsonSubTypes.Type(value = Narration.Answered.class, name = "answered"),
  @JsonSubTypes.Type(value = Narration.TurnStopped.class, name = "turn-stopped"),
  @JsonSubTypes.Type(value = Narration.TurnFailed.class, name = "turn-failed"),
  @JsonSubTypes.Type(value = Narration.TurnRefused.class, name = "turn-refused"),
  @JsonSubTypes.Type(value = Narration.InferenceRetried.class, name = "inference-retried"),
  @JsonSubTypes.Type(value = Narration.Commentary.class, name = "commentary"),
  @JsonSubTypes.Type(value = Narration.ActionsRequested.class, name = "actions-requested"),
  @JsonSubTypes.Type(value = Narration.CallApproved.class, name = "call-approved"),
  @JsonSubTypes.Type(value = Narration.CallDenied.class, name = "call-denied"),
  @JsonSubTypes.Type(value = Narration.CallFinished.class, name = "call-finished"),
  @JsonSubTypes.Type(value = Narration.CallFailed.class, name = "call-failed"),
  @JsonSubTypes.Type(value = Narration.Terminated.class, name = "terminated"),
  @JsonSubTypes.Type(value = Narration.ApprovalSought.class, name = "approval-sought"),
  @JsonSubTypes.Type(value = Narration.ApprovalDeferred.class, name = "approval-deferred"),
  @JsonSubTypes.Type(value = Narration.CallDeferred.class, name = "call-deferred"),
  @JsonSubTypes.Type(value = Narration.ThinkingDelta.class, name = "thinking-delta"),
  @JsonSubTypes.Type(value = Narration.ContentDelta.class, name = "content-delta")
})
public sealed interface Narration {

  /**
   * An event the agent's story records: stored, and told the same way however many times it is
   * replayed.
   */
  sealed interface Story extends Narration {}

  /**
   * A signal heard only as it happens: nothing is stored, so a watcher that was not listening never
   * hears it.
   */
  sealed interface Live extends Narration {}

  /**
   * One of the ways a turn ends: {@link Answered}, {@link TurnRefused}, {@link TurnFailed} or
   * {@link TurnStopped}. A turn ends in exactly one, so a handler for this hears the story grow by
   * a turn without caring how.
   */
  sealed interface TurnEnding extends Story {
    TurnId turn();
  }

  // ---- story: stored, and heard once the fold that wrote it has committed ---------------

  /**
   * A turn opened. An input was taken up and a turn opened on it.
   *
   * <p>The input is not echoed here. Whoever sent it has it, and anybody else reads the story;
   * narration says what is happening, and repeating content into it makes every watcher pay to be
   * told what it already had.
   *
   * <p>{@code label} says what started the turn, and {@code arrivedAt} is when its input reached
   * the harness. How long the input waited is this event's time minus {@code arrivedAt}.
   */
  record TurnStarted(TurnId turn, String label, Instant arrivedAt) implements Story {}

  /**
   * The turn produced an answer, and ended on it.
   *
   * <p>Not the answer itself. The direct door returns it to the caller who asked, and anything
   * watching a queued agent reads it from the story; a provider that streams has already said it
   * delta by delta. Carrying it here would be a third copy of the same words.
   *
   * @param truncated whether the model was cut off at its output limit, so the answer stops short
   * @param usage what the model call cost, as the vendor counted it
   */
  record Answered(TurnId turn, boolean truncated, Usage usage) implements TurnEnding {}

  /**
   * The turn was stopped on purpose, and this is why.
   *
   * <p>Not a model call that failed: a policy decided the turn had gone on long enough, and no call
   * was made. So it carries a reason and no {@link Usage}.
   *
   * @param reason the policy's account of why it stopped the turn
   */
  record TurnStopped(TurnId turn, String reason) implements TurnEnding {}

  /**
   * The turn ended without an answer because a model call failed, and might have gone otherwise.
   *
   * <p><b>Carries why, because the queued door has no inline answer.</b> The direct door hands the
   * reason back from {@code ask} as an {@code AskOutcome.Failed}; the queued door's {@code tell}
   * returns only a {@code TellOutcome}, so a watcher hears the reason here as it happens, and
   * {@code AgentStories.replay} reads it afterwards.
   *
   * @param kind what is known about whether trying again could work
   * @param reason the provider adapter's account of what went wrong, the same text the direct door
   *     returns
   * @param usage what the failed call cost, which is often nothing counted
   */
  record TurnFailed(TurnId turn, FailureKind kind, String reason, Usage usage)
      implements TurnEnding {}

  /**
   * The turn was declined, and would be declined again.
   *
   * <p>Carries the category for the same reason {@link TurnFailed} carries its text. A refusal is
   * not a failure -- the call succeeded and the model chose not to answer -- and the category is
   * the whole of what it said about choosing. It still costs: the model read the input first.
   *
   * @param category the provider's own word for why, unchanged and uninterpreted
   * @param usage what the call cost
   */
  record TurnRefused(TurnId turn, String category, Usage usage) implements TurnEnding {}

  /**
   * A model call failed and was tried again.
   *
   * <p>The turn carries on, which is what tells this apart from {@link TurnFailed}. It is told
   * because the attempt cost something and nothing else says so.
   *
   * @param kind {@link FailureKind#TRANSIENT} for a provider that said it might work next time,
   *     {@link FailureKind#UNKNOWN} for a call nobody heard back from
   * @param reason what went wrong, in words
   * @param usage what the attempt cost
   */
  record InferenceRetried(TurnId turn, FailureKind kind, String reason, Usage usage)
      implements Story {}

  /**
   * The model asked for work before it would answer.
   *
   * <p>One entry per call, each carrying the call's id, because every later event about a call --
   * {@link CallApproved}, {@link CallDenied}, {@link CallFinished}, {@link CallFailed} -- names the
   * id and the call's key, never the tool. This is where a watcher learns which tool that id is.
   *
   * @param usage what the model call that asked for the work cost
   */
  record ActionsRequested(TurnId turn, List<Call> calls, Usage usage) implements Story {
    public ActionsRequested {
      calls = List.copyOf(calls);
    }

    /**
     * One call the model asked for.
     *
     * <p>Carries the action -- the sentence a person is shown, the same one {@link ApprovalSought}
     * carries -- and not the arguments. Narration says what is happening; the arguments are
     * content, and they are in the story.
     *
     * @param callId the id every later event about this call carries
     * @param idempotencyKey the key the call runs under, stable across every retry of it
     * @param toolName the tool asked for
     * @param action the sentence the binding's stringifier wrote for this call
     */
    public record Call(
        CallId callId, IdempotencyKey idempotencyKey, ToolName toolName, String action) {}
  }

  /**
   * A call was allowed to run.
   *
   * <p>Names the call and not the tool, because the entry this is derived from does not carry the
   * tool's name and inventing a lookup to fill the field would make the announcement claim
   * something the story does not. A watcher that wants the name joins by key to the {@link
   * ActionsRequested.Call} it heard a moment ago: the same {@code idempotencyKey} is on the
   * request, the decision and the outcome.
   *
   * @param callId the id the call was requested with
   * @param idempotencyKey the call's own key, the same on its request, its approval and its outcome
   * @param decidedBy who or what decided, as the application said it; empty when nobody is named.
   *     Nessy never interprets it.
   */
  record CallApproved(CallId callId, IdempotencyKey idempotencyKey, Optional<String> decidedBy)
      implements Story {}

  /**
   * A call was refused, and never ran.
   *
   * @param callId the id the call was requested with
   * @param idempotencyKey the call's own key, the same on its request, its approval and its outcome
   * @param reason why the call was refused
   * @param decidedBy who or what refused it, as the application said it; empty when nobody is
   *     named. Nessy never interprets it.
   */
  record CallDenied(
      CallId callId, IdempotencyKey idempotencyKey, String reason, Optional<String> decidedBy)
      implements Story {}

  /**
   * A call ran and produced something.
   *
   * @param callId the id the call was requested with
   * @param idempotencyKey the call's own key, the same on its request, its approval and its outcome
   */
  record CallFinished(CallId callId, IdempotencyKey idempotencyKey) implements Story {}

  /**
   * A call did not produce something. The message is what the model will read.
   *
   * @param callId the id the call was requested with
   * @param idempotencyKey the call's own key, the same on its request, its approval and its outcome
   * @param kind why it did not: it failed, it ran past its deadline, or it was never authorised
   * @param message what the model will read for the call
   */
  record CallFailed(CallId callId, IdempotencyKey idempotencyKey, CallFailure kind, String message)
      implements Story {}

  /** The agent will accept nothing further. */
  record Terminated() implements Story {}

  /**
   * A call waiting on an approval was put aside: nobody has answered yet, and the approval request
   * stands until {@code until}.
   *
   * <p>Stored, so it is heard once the fold that wrote it has committed and told the same way on
   * every replay. Names the call and its key and not the action: a watcher that wants the action
   * joins by key to the {@link ActionsRequested.Call} it heard earlier.
   *
   * @param callId the id the call was requested with
   * @param idempotencyKey the call's own key, the same on its request and on everything after it
   * @param until when the approval request stops standing
   */
  record ApprovalDeferred(CallId callId, IdempotencyKey idempotencyKey, Instant until)
      implements Story {}

  /**
   * A running call was put aside: the tool started work and will report back by {@code until}.
   * Stored, and told as {@link ApprovalDeferred} is; a watcher joins by key to the {@link
   * ActionsRequested.Call} for the tool's name.
   *
   * @param callId the id the call was requested with
   * @param idempotencyKey the call's own key, the same on its request and on everything after it
   * @param until when the call stops standing
   */
  record CallDeferred(CallId callId, IdempotencyKey idempotencyKey, Instant until)
      implements Story {}

  // ---- live: heard only as it happens ---------------------------------------------------

  /** The model is being asked. Narrated before the call, so a watcher can show waiting. */
  record Thinking() implements Live {}

  /**
   * What the model said while asking for work -- "Let me look that up."
   *
   * <p>Its own event rather than a field on {@link ActionsRequested}, because it is a different
   * kind of thing to show: prose a person reads, beside a list of machinery. A console prints one
   * as a sentence and the other as a list, and a watcher that wants only one can take it.
   *
   * <p>Announced only when the model actually said something -- plenty of calls arrive with no
   * prose at all, and an empty line is worse than none.
   *
   * <p>Needs no block kind of its own to be told apart from an answer: the same {@code Text} inside
   * a request for actions is commentary and inside an answer is the answer. The grammar says which
   * by where it sits.
   */
  record Commentary(String text) implements Live {}

  // -- waiting: the reason this channel exists

  /**
   * Somebody is being asked whether a call may run.
   *
   * <p>Carries {@code action} -- the sentence a person is shown -- because an operator watching an
   * agent wants to know what is being asked, not which call id is outstanding.
   */
  record ApprovalSought(CallId callId, String action) implements Live {}

  // -- deltas: from a provider, while a call is in flight

  /**
   * A fragment of reasoning, as it arrives.
   *
   * <p><b>There is no thinking block, and there should not be one.</b> What is durable about
   * reasoning is {@code Block.Provider}: vendor-tagged, opaque, signed or encrypted, and handed
   * back byte-for-byte without anything here looking inside. That is the right way to keep it and a
   * useless way to show it -- so this is the only form a watcher can read, and it exists only while
   * the call is in flight.
   *
   * <p>Which is also why it is an event rather than anything stored. Reasoning is not portable: one
   * vendor streams it as its own channel, another exposes only a summary, another encrypts it, and
   * plenty of local models emit it inline in the content with no channel at all. An adapter whose
   * wire has no such notion simply never narrates one, and nothing downstream is waiting. A block
   * would have had to be stored and re-sent, and then portability binds.
   *
   * <p>The arm most likely to be filtered: an operator's console may want it and an end user's may
   * not.
   */
  record ThinkingDelta(String text) implements Live {}

  /**
   * A fragment of the answer, as it arrives.
   *
   * <p>The same text that lands in the story a moment later as an answer, arriving early. Not a
   * second copy of anything: a watcher that missed every delta still sees {@link Answered}.
   */
  record ContentDelta(String text) implements Live {}
}
