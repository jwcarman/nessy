package org.jwcarman.nessy.engine.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * A durable obligation to do work outside the transition. An effect is never proof that the work
 * happened -- only that it is owed.
 *
 * <p>An effect says what to do and nothing about how to run it: no retry policy, no timeout. Those
 * are terms of the binding it came from and travel in an {@link EffectRequest} alongside it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AgentEffect.Infer.class, name = "infer"),
  @JsonSubTypes.Type(value = AgentEffect.Approve.class, name = "approve"),
  @JsonSubTypes.Type(value = AgentEffect.CallTool.class, name = "call-tool")
})
public sealed interface AgentEffect {

  /**
   * Ask the model to answer, given the conversation so far.
   *
   * <p>The messages travel in the effect rather than being looked up when it runs, so whatever
   * executes this needs nothing but the row: the request is exactly what the fold decided it should
   * be, even if the agent has moved on since.
   */
  record Infer() implements AgentEffect {}

  /**
   * Run one tool the model asked for.
   *
   * <p><b>An address, not a copy.</b> The call itself -- its name, its arguments -- is already
   * written down in the entry at {@code requestSeq}, and duplicating it into the effect row would
   * make two records of the same fact that a migration or a fix could put out of step. Whatever
   * performs this resolves the address against history, which is the one copy.
   *
   * <p>That is the opposite of {@link Infer}'s reasoning, and the difference is what each one
   * needs. An inference needs a window of the conversation that only makes sense as of now; a tool
   * call needs one immutable row that will read the same forever.
   *
   * <p><b>The name is the one thing copied, and it earns it.</b> What a call of this tool is worth
   * -- its timeout, how hard it is worth retrying, who must approve it -- is stated per tool, and
   * those terms are read while the row is being written, inside the fold's transaction, before
   * anything has been decoded or looked up. A row that named only its address would have to go and
   * read the story to find out what it is worth, which is a query inside the one transaction that
   * must not grow. Everything else about the call stays where it was written once.
   *
   * @param requestSeq the seq of the {@code InferenceRequestedActions} entry holding the call
   * @param callId which call within it, by the id the model gave it
   * @param toolName what the model asked for, which may no longer be bound to anything
   */
  record CallTool(Seq requestSeq, CallId callId, ToolName toolName) implements AgentEffect {}

  /**
   * Find out whether one call may run.
   *
   * <p><b>Every call goes through this, including the ones nothing is gating.</b> An unbound
   * approver answers yes immediately and the row is gone in a millisecond, which is a real cost and
   * a small one -- and it buys a lifecycle with no second shape. A design that skipped the question
   * when no approver was configured would have two paths into a running tool, and the one that was
   * never exercised is the one a misconfiguration would silently take.
   *
   * <p>Its own effect rather than a step inside {@link CallTool}, because asking is work that can
   * fail on its own terms and has its own budget: an approval service is a different thing to reach
   * than the tool, a person takes minutes where a lookup takes seconds, and a failure to
   * <em>ask</em> is worth repeating where a failure to run a tool that may already have run is not.
   * Folded into the call, one retry policy would have had to mean both.
   *
   * <p>Never written to the story on its own. Permission granted is not something that happened in
   * the conversation, and the model has no use for it; permission refused is, and that is what
   * {@code ToolDenied} is for.
   */
  record Approve(Seq requestSeq, CallId callId, ToolName toolName) implements AgentEffect {}
}
