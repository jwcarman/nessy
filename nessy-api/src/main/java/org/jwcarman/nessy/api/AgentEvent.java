package org.jwcarman.nessy.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;
import org.jwcarman.nessy.api.tool.CallId;
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
 * org.jwcarman.nessy.api.block.Block}, an event names its kind in JSON. The names are the kinds in
 * kebab-case, and they are the event names such a narrator uses on the wire too.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AgentEvent.TurnStarted.class, name = "turn-started"),
  @JsonSubTypes.Type(value = AgentEvent.Thinking.class, name = "thinking"),
  @JsonSubTypes.Type(value = AgentEvent.Answered.class, name = "answered"),
  @JsonSubTypes.Type(value = AgentEvent.TurnFailed.class, name = "turn-failed"),
  @JsonSubTypes.Type(value = AgentEvent.TurnRefused.class, name = "turn-refused"),
  @JsonSubTypes.Type(value = AgentEvent.Commentary.class, name = "commentary"),
  @JsonSubTypes.Type(value = AgentEvent.ActionsRequested.class, name = "actions-requested"),
  @JsonSubTypes.Type(value = AgentEvent.CallApproved.class, name = "call-approved"),
  @JsonSubTypes.Type(value = AgentEvent.CallDenied.class, name = "call-denied"),
  @JsonSubTypes.Type(value = AgentEvent.CallFinished.class, name = "call-finished"),
  @JsonSubTypes.Type(value = AgentEvent.CallFailed.class, name = "call-failed"),
  @JsonSubTypes.Type(value = AgentEvent.Terminated.class, name = "terminated"),
  @JsonSubTypes.Type(value = AgentEvent.ApprovalSought.class, name = "approval-sought"),
  @JsonSubTypes.Type(value = AgentEvent.ApprovalDeferred.class, name = "approval-deferred"),
  @JsonSubTypes.Type(value = AgentEvent.CallDeferred.class, name = "call-deferred"),
  @JsonSubTypes.Type(value = AgentEvent.ThinkingDelta.class, name = "thinking-delta"),
  @JsonSubTypes.Type(value = AgentEvent.ContentDelta.class, name = "content-delta")
})
public sealed interface AgentEvent {

  // ---- facts: from the engine, after the fold commits ---------------------------------

  /** An observation was taken up and a turn opened on it. */
  record TurnStarted(TurnId turn, String observation) implements AgentEvent {}

  /** The model is being asked. Narrated before the call, so a watcher can show waiting. */
  record Thinking() implements AgentEvent {}

  /**
   * The turn ended with an answer.
   *
   * <p>Carries the text, unlike most facts here, because a watcher that cannot show the answer is
   * not much of a watcher -- and a provider that does not stream has narrated no deltas, so this is
   * the only place the answer appears. Text rather than blocks: the block grammar is the engine's
   * business, and what a watcher wants is what a person would read.
   */
  record Answered(String text) implements AgentEvent {}

  /** The turn ended without an answer, and might have gone otherwise. */
  record TurnFailed() implements AgentEvent {}

  /** The turn was declined, and would be declined again. */
  record TurnRefused() implements AgentEvent {}

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
  record Commentary(String text) implements AgentEvent {}

  /** The model asked for work before it would answer. */
  record ActionsRequested(List<ToolName> toolNames) implements AgentEvent {
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
  record CallApproved(CallId callId) implements AgentEvent {}

  /** A call was refused, and never ran. */
  record CallDenied(CallId callId, String reason) implements AgentEvent {}

  /** A call ran and produced something. */
  record CallFinished(CallId callId) implements AgentEvent {}

  /** A call did not produce something. The message is what the model will read. */
  record CallFailed(CallId callId, String message) implements AgentEvent {}

  /** The agent will accept nothing further. */
  record Terminated() implements AgentEvent {}

  // ---- waiting: the reason this channel exists ----------------------------------------

  /**
   * Somebody is being asked whether a call may run.
   *
   * <p>Carries {@code action} -- the sentence a person is shown -- because an operator watching an
   * agent wants to know what is being asked, not which call id is outstanding.
   */
  record ApprovalSought(CallId callId, String action) implements AgentEvent {}

  /**
   * Nobody has answered yet, and the question stands until {@code until}.
   *
   * <p><b>The arm that pays for this whole channel.</b> "Awaiting a human" is the state an operator
   * most wants to see, and the engine deliberately does not record it: the fold cannot tell a tool
   * that takes three days from one that takes 200ms, and should not learn. So it is announced
   * rather than stored, which is the one place it belongs.
   */
  record ApprovalDeferred(CallId callId, String action, Instant until) implements AgentEvent {}

  /** A tool started work and will report back. Same reasoning as {@link ApprovalDeferred}. */
  record CallDeferred(CallId callId, ToolName toolName, Instant until) implements AgentEvent {}

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
  record ThinkingDelta(String text) implements AgentEvent {}

  /**
   * A fragment of the answer, as it arrives.
   *
   * <p>The same text that lands in the story a moment later as an answer, arriving early. Not a
   * second copy of anything: a watcher that missed every delta still sees {@link Answered}.
   */
  record ContentDelta(String text) implements AgentEvent {}
}
