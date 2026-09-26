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
import org.jwcarman.nessy.inference.TurnId;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.inference.tool.ToolName;

/**
 * Something an agent did, announced to whoever is watching.
 *
 * <p><b>An event is not an entry.</b> {@code HistoryEntry} is what is durable -- written in the
 * fold's transaction, re-read forever, and the thing a model is eventually shown. This is what is
 * <em>announced</em>: delivered once, to whoever happens to be listening, and gone. Losing one
 * costs a watcher a line; the fact it described is still in the story.
 *
 * <p>That difference is what makes the two vocabularies diverge rather than mirror each other.
 * Plenty here leaves no entry at all -- a call waiting on a person, a token arriving mid-sentence
 * -- and plenty of entries are not worth announcing. Trying to derive one from the other would
 * force both to be shaped by the other's needs.
 *
 * <p><b>Two tiers, and the difference is who says them.</b> Facts come from the engine, after a
 * fold has committed, so by the time one is announced it is true. Deltas come from a provider while
 * a call is still in flight, and are true only of that attempt -- a call that fails and is retried
 * narrates twice, and a watcher should treat deltas as what is being said rather than as what was
 * said.
 *
 * <p><b>No timestamp and no agent.</b> A sink stamps events if it cares, rather than every delta
 * paying for a clock read; and identity is passed beside the event by {@link Narrator}, which is
 * what lets a provider narrate without ever being told which agent it is serving.
 *
 * <p><b>Typed on the wire.</b> An event is announced and forgotten by the engine, but a narrator
 * may journal it and a page may read it back later -- so, like {@link
 * org.jwcarman.nessy.inference.block.Block}, an event names its kind in JSON. The names are the
 * kinds in kebab-case, and they are the event names such a narrator uses on the wire too.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Narration.TurnStarted.class, name = "turn-started"),
  @JsonSubTypes.Type(value = Narration.Thinking.class, name = "thinking"),
  @JsonSubTypes.Type(value = Narration.Answered.class, name = "answered"),
  @JsonSubTypes.Type(value = Narration.TurnEnded.class, name = "turn-ended"),
  @JsonSubTypes.Type(value = Narration.TurnFailed.class, name = "turn-failed"),
  @JsonSubTypes.Type(value = Narration.TurnRefused.class, name = "turn-refused"),
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

  // ---- facts: from the engine, after the fold commits ---------------------------------

  /** An input was taken up and a turn opened on it. */
  /**
   * A turn opened.
   *
   * <p>The input is not echoed here. Whoever sent it has it, and anybody else reads the story;
   * narration says what is happening, and repeating content into it makes every watcher pay to be
   * told what it already had.
   */
  record TurnStarted(TurnId turn) implements Narration {}

  /** The model is being asked. Narrated before the call, so a watcher can show waiting. */
  record Thinking() implements Narration {}

  /**
   * The turn ended with an answer.
   *
   * <p>Carries the text, unlike most facts here, because a watcher that cannot show the answer is
   * not much of a watcher -- and a provider that does not stream has narrated no deltas, so this is
   * the only place the answer appears. Text rather than blocks: the block grammar is the engine's
   * business, and what a watcher wants is what a person would read.
   */
  /**
   * The turn produced an answer.
   *
   * <p>Not the answer itself. The direct door returns it to the caller who asked, and anything
   * watching a queued agent reads it from the story; a provider that streams has already said it
   * delta by delta. Carrying it here would be a third copy of the same words.
   */
  record Answered() implements Narration {}

  /**
   * The turn is over, however it ended -- answered, failed or refused. One event to listen for when
   * what matters is that the story grew by a turn, not how.
   */
  record TurnEnded(TurnId turn) implements Narration {}

  /** The turn ended without an answer, and might have gone otherwise. */
  record TurnFailed() implements Narration {}

  /** The turn was declined, and would be declined again. */
  record TurnRefused() implements Narration {}

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
  record Commentary(String text) implements Narration {}

  /** The model asked for work before it would answer. */
  record ActionsRequested(List<ToolName> toolNames) implements Narration {
    public ActionsRequested {
      toolNames = List.copyOf(toolNames);
    }
  }

  /**
   * A call was allowed to run.
   *
   * <p>Names the call and not the tool, because the entry this is derived from does not carry the
   * tool's name and inventing a lookup to fill the field would make the announcement claim
   * something the story does not. A watcher that wants the name heard it a moment ago in {@link
   * ActionsRequested}.
   */
  record CallApproved(CallId callId) implements Narration {}

  /** A call was refused, and never ran. */
  record CallDenied(CallId callId, String reason) implements Narration {}

  /** A call ran and produced something. */
  record CallFinished(CallId callId) implements Narration {}

  /** A call did not produce something. The message is what the model will read. */
  record CallFailed(CallId callId, String message) implements Narration {}

  /** The agent will accept nothing further. */
  record Terminated() implements Narration {}

  // ---- waiting: the reason this channel exists ----------------------------------------

  /**
   * Somebody is being asked whether a call may run.
   *
   * <p>Carries {@code action} -- the sentence a person is shown -- because an operator watching an
   * agent wants to know what is being asked, not which call id is outstanding.
   */
  record ApprovalSought(CallId callId, String action) implements Narration {}

  /**
   * Nobody has answered yet, and the question stands until {@code until}.
   *
   * <p><b>The arm that pays for this whole channel.</b> "Awaiting a human" is the state an operator
   * most wants to see, and the engine deliberately does not record it: the fold cannot tell a tool
   * that takes three days from one that takes 200ms, and should not learn. So it is announced
   * rather than stored, which is the one place it belongs.
   */
  record ApprovalDeferred(CallId callId, String action, Instant until) implements Narration {}

  /** A tool started work and will report back. Same reasoning as {@link ApprovalDeferred}. */
  record CallDeferred(CallId callId, ToolName toolName, Instant until) implements Narration {}

  // ---- deltas: from a provider, while a call is in flight -------------------------------

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
  record ThinkingDelta(String text) implements Narration {}

  /**
   * A fragment of the answer, as it arrives.
   *
   * <p>The same text that lands in the story a moment later as an answer, arriving early. Not a
   * second copy of anything: a watcher that missed every delta still sees {@link Answered}.
   */
  record ContentDelta(String text) implements Narration {}
}
