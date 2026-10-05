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
package org.jwcarman.nessy.api.tool;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The approval request an approver answers: this agent wants to make this call, and here is what it
 * means.
 *
 * <p><b>Built to be kept elsewhere.</b> An approver may defer, which leaves the request open until
 * a person answers it -- possibly days later, in another process, on a page that never saw the
 * agent. The application may keep the request itself for that (an approvals page, a ticket), so
 * everything here must survive being written down and read back. Nessy does not keep the request:
 * it records the request's {@link #facts() facts} on the event that records the decision, or the
 * deferral before it, or the failure, and nothing else of the request, because the rest is already
 * in the story.
 *
 * <p><b>{@link #action()} is fixed here, at ask time, and never re-derived.</b> A person must be
 * answering the same sentence that was shown to them, not one recomputed later from arguments whose
 * meaning may have moved, by a stringifier somebody has since edited.
 *
 * <p><b>A carrier, not a value.</b> The engine builds the approval request; {@link
 * ApprovalEnricher}s add to it. {@link #fact(String, JsonNode)} writes onto this request in place,
 * and whatever has been written by the time an approver returns is what a person eventually reads.
 * That is the point: an enricher that resolves a principal or scores a risk has somewhere to put
 * what it found, so the request that reaches a person is complete rather than one sentence.
 *
 * <p>Because it is a carrier, do not treat it as a value: it is mutable, so it is not safe as a map
 * key, not safe to share across threads without care, and not guaranteed to stay as you found it.
 *
 * <p><b>An approval is not a tool call, and its failures are a different kind.</b> A {@link
 * ApprovalResult.Denied} is an answer -- the approver was reached and said no -- so nothing about
 * it is worth attempting again. Asking twice until somebody relents is not a retry, it is
 * pestering, and an engine that did it would eventually get the answer it wanted. The only thing
 * here that can fail and deserve another attempt is <em>delivering the approval request</em>.
 *
 * @param agentType what kind of agent is asking -- a shared approvals page shows calls from several
 *     kinds side by side, and an id alone does not say which is which
 * @param agentId which agent is asking
 * @param turn the turn this call belongs to, which is the seq of the input that opened it; to tell
 *     one call from another, use {@code idempotencyKey}, not this with {@code callId}
 * @param callId which call within that turn, by the id the model gave it
 * @param idempotencyKey the call's own key, the same here as on the {@link ToolCallRequest} that
 *     runs it, and on every ask of it -- see {@link IdempotencyKey}
 * @param toolName what the model asked for
 * @param arguments what it asked for, as the model wrote it -- unparsed, and shown rather than
 *     trusted
 * @param action what will actually happen if this is approved, in words a person can consent to;
 *     the binding's action stringifier ({@link ToolConfig#action}) produced it
 * @param askedAt when the approval request was raised -- dwell time on an approvals page, and the
 *     fixed point the deadline was measured from, so a restart cannot silently extend one
 * @param deadline when the approval request stops standing
 * @param facts whatever enrichers have added; empty when the engine first builds it
 */
public record ApprovalRequest(
    AgentType agentType,
    AgentId agentId,
    TurnId turn,
    CallId callId,
    IdempotencyKey idempotencyKey,
    ToolName toolName,
    String arguments,
    String action,
    Instant askedAt,
    Instant deadline,
    ReplyToken replyToken,
    ObjectNode facts) {

  /** The approval request as the engine first asks it: nothing has annotated it yet. */
  public ApprovalRequest(
      AgentType agentType,
      AgentId agentId,
      TurnId turn,
      CallId callId,
      IdempotencyKey idempotencyKey,
      ToolName toolName,
      String arguments,
      String action,
      Instant askedAt,
      Instant deadline,
      ReplyToken replyToken) {
    this(
        agentType,
        agentId,
        turn,
        callId,
        idempotencyKey,
        toolName,
        arguments,
        action,
        askedAt,
        deadline,
        replyToken,
        JsonNodeFactory.instance.objectNode());
  }

  public ApprovalRequest {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(arguments, "arguments must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(askedAt, "askedAt must not be null");
    Objects.requireNonNull(deadline, "deadline must not be null");
    Objects.requireNonNull(replyToken, "replyToken must not be null");
    Objects.requireNonNull(facts, "facts must not be null");
  }

  /**
   * Adds a fact, replacing any already recorded under {@code name}. Returns this request, so an
   * enricher can chain.
   *
   * <p><b>Namespace the name</b> when the enricher is not the application's own code -- {@code
   * "risk.score"}, {@code "quota.remaining"}, {@code "policy.term"} -- so two modules annotating
   * the same approval request cannot collide. Names rather than typed keys on purpose: a typed bag
   * would make this package own a vocabulary of facts, which is exactly the thing it is trying not
   * to own. The two sides agree by convention, and a module publishes its convention as a constant.
   *
   * <p>A tree rather than text, unlike every other JSON on this API. The rule is what anybody does
   * with it: a schema and a call's arguments are only ever <em>moved</em> -- generated once, handed
   * on, re-sent -- so they are text, while facts exist to be <em>read</em>, by a policy that weighs
   * them and a page that renders them.
   */
  public ApprovalRequest fact(String name, JsonNode value) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(value, "value must not be null");
    facts.set(name, value);
    return this;
  }

  /** A text fact, which is most of them. */
  public ApprovalRequest fact(String name, String value) {
    Objects.requireNonNull(value, "value must not be null");
    return fact(name, JsonNodeFactory.instance.stringNode(value));
  }

  /** What some enricher recorded under {@code name}, if anything. */
  public Optional<JsonNode> fact(String name) {
    Objects.requireNonNull(name, "name must not be null");
    return Optional.ofNullable(facts.get(name));
  }

  /**
   * Where a person's answer goes, if this approver defers.
   *
   * <p>Kept apart from the rest in how it is read and logged, because it is not part of what a
   * person is shown: the rest of the request is what an approvals page renders, and only its facts
   * are recorded. The token is the authority to settle the call. A credential has no business in a
   * projection.
   */
  @Override
  public ReplyToken replyToken() {
    return replyToken;
  }

  /** The reply address is absent: it is a credential, and this may reach a log. */
  @Override
  public String toString() {
    return ("ApprovalRequest[agentType=%s, agentId=%s, turn=%s, callId=%s, idempotencyKey=%s,"
            + " toolName=%s, action=%s, askedAt=%s, deadline=%s, facts=%s]")
        .formatted(
            agentType,
            agentId,
            turn,
            callId,
            idempotencyKey,
            toolName,
            action,
            askedAt,
            deadline,
            facts);
  }
}
