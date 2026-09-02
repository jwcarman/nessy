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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * The question an approver answers: this agent wants to make this call, and here is what it means.
 *
 * <p><b>A document, by contract.</b> An approver may defer, which parks the question until a person
 * answers it — possibly days later, in another process, rendered by a page that never saw the
 * agent. So this must serialize, and the rendered document is the record of what was decided on.
 * {@code description} is fixed HERE, at ask time, and never re-derived at read time: a human must
 * be answering the same sentence that was shown to them, not one recomputed later from arguments
 * whose meaning may have moved.
 *
 * <p><b>A carrier, not a value.</b> The framework bootstraps the question; an approver adds to it.
 * {@link #fact(String, JsonNode)} writes onto this request in place, and whatever an approver has
 * written when it returns is what gets parked and shown to a human. That is the point: a custom
 * approver that resolves a principal or scores risk has somewhere to put its findings, so the
 * parked document stays complete and a page rendering it later can show more than one sentence.
 *
 * <p>Because it is a carrier, do not treat it as a value: it is mutable, so it is not safe to use
 * as a map key, to share across threads without care, or to hold onto expecting it to stay as you
 * found it.
 *
 * <p><b>What is deliberately absent.</b> No enrichment pipeline, no typed fact keys, no risk
 * vocabulary, no mutable draft type. Policy is not this module's business: the API states the
 * question and the shape of an answer, and opinionated approvers — risk scoring, principal
 * resolution, quota checks, human desks — arrive as separate modules built on {@link Approver}.
 * Facts are untyped for the same reason: a typed bag would make this module own the vocabulary it
 * is trying not to own.
 *
 * @param agentType what kind of agent is asking — a shared approvals page shows calls from several
 *     kinds side by side, and an id alone does not say which is which
 * @param agentId which agent is asking
 * @param call what it wants to do
 * @param action what will actually happen if this is approved, in words a person can consent to —
 *     the binding's {@code ActionRenderer} rendered it. Not "description": a {@link Tool} has one
 *     of those, and it says what the tool IS. This says what this CALL would do.
 * @param askedAt when the question was raised — dwell time on a pending-approvals page, and the
 *     fixed point a deadline is measured from, so a restart cannot silently extend one
 * @param arguments what the model asked for, as JSON — see the note on JSON below
 * @param facts whatever approvers have added so far; empty when the framework first asks
 */
public record ApprovalRequest(
    AgentType agentType,
    AgentId agentId,
    String turnId,
    String callId,
    String toolName,
    JsonNode arguments,
    String action,
    Instant askedAt,
    Supplier<ReplyToken> replyTokens,
    ObjectNode facts) {

  public ApprovalRequest {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(turnId, "turnId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(arguments, "arguments must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(askedAt, "askedAt must not be null");
    Objects.requireNonNull(replyTokens, "replyTokens must not be null");
    Objects.requireNonNull(facts, "facts must not be null");
  }

  /** The question as the harness first asks it: nothing has annotated it yet. */
  public ApprovalRequest(
      AgentType agentType,
      AgentId agentId,
      String turnId,
      String callId,
      String toolName,
      JsonNode arguments,
      String action,
      Instant askedAt,
      Supplier<ReplyToken> replyTokens) {
    this(
        agentType,
        agentId,
        turnId,
        callId,
        toolName,
        arguments,
        action,
        askedAt,
        replyTokens,
        JsonNodeFactory.instance.objectNode());
  }

  /** Where a person's answer goes, if this approver defers. Minted on demand, then remembered. */
  public ReplyToken replyToken() {
    return replyTokens.get();
  }

  /**
   * The key this call is identified by across a re-drive.
   *
   * <p>The turn and the call together, because a model's call id is unique within one response only
   * — two turns can each produce a "call_1".
   */
  public String callKey() {
    return turnId + "/" + callId;
  }

  /**
   * Adds a fact, replacing any already recorded under {@code name}. Returns this request so an
   * approver can chain.
   *
   * <p>Namespace the name when the approver is not the application's own — {@code "risk.score"},
   * {@code "quota.remaining"} — so two modules annotating the same question do not collide.
   */
  public ApprovalRequest fact(String name, JsonNode value) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(value, "value must not be null");
    facts.set(name, value);
    return this;
  }

  /** Adds a text fact — the common case. */
  public ApprovalRequest fact(String name, String value) {
    Objects.requireNonNull(value, "value must not be null");
    return fact(name, JsonNodeFactory.instance.textNode(value));
  }

  /** What some approver recorded under {@code name}, if anything. */
  public Optional<JsonNode> fact(String name) {
    Objects.requireNonNull(name, "name must not be null");
    return Optional.ofNullable(facts.get(name));
  }

  /**
   * Equality over the question, ignoring how its address would be minted.
   *
   * <p>Written out for the same reason {@code ToolCallRequest}'s is: a record would compare the
   * supplier by identity, so two requests about the same call would differ.
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof ApprovalRequest request
        && Objects.equals(agentType, request.agentType)
        && Objects.equals(agentId, request.agentId)
        && Objects.equals(turnId, request.turnId)
        && Objects.equals(callId, request.callId)
        && Objects.equals(toolName, request.toolName)
        && Objects.equals(arguments, request.arguments)
        && Objects.equals(action, request.action)
        && Objects.equals(askedAt, request.askedAt)
        && Objects.equals(facts, request.facts);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        agentType, agentId, turnId, callId, toolName, arguments, action, askedAt, facts);
  }

  /** The reply address is absent: it is a credential, and this may reach a log. */
  @Override
  public String toString() {
    return "ApprovalRequest[agentId=%s, callId=%s, toolName=%s, action=%s, facts=%s]"
        .formatted(agentId, callId, toolName, action, facts);
  }
}
