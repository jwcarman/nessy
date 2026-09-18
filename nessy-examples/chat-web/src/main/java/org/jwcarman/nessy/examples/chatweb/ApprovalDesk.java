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
package org.jwcarman.nessy.examples.chatweb;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The questions waiting for a person, in memory.
 *
 * <p>In memory because this is an example: a restart loses the cards, though not the questions --
 * the engine still holds each call open until its deadline, and a real desk would keep the token
 * somewhere durable. The reply token is held here and never rendered; it is the credential that
 * answers the question, not a fact about it.
 */
@Component
public class ApprovalDesk {

  private static final JsonMapper EVIDENCE = JsonMapper.builder().build();

  /** The arguments, pretty-printed for a person, or as they came if they will not parse. */
  private static String evidenceOf(String arguments) {
    try {
      return EVIDENCE
          .writerWithDefaultPrettyPrinter()
          .writeValueAsString(EVIDENCE.readTree(arguments));
    } catch (JacksonException notJson) {
      return arguments;
    }
  }

  public record Waiting(
      AgentId agentId,
      CallId callId,
      String tool,
      String arguments,
      String description,
      Instant askedAt,
      ReplyToken replyToken) {}

  private final ConcurrentMap<CallId, Waiting> waiting = new ConcurrentHashMap<>();

  public void expecting(ApprovalRequest request) {
    waiting.put(
        request.callId(),
        new Waiting(
            request.agentId(),
            request.callId(),
            request.toolName().value(),
            evidenceOf(request.arguments()),
            request.action(),
            request.askedAt(),
            request.replyToken()));
  }

  /** A question as the page draws it. The token is not on it; a card is not a credential. */
  public record Card(String id, String tool, String args, String what, Instant askedAt) {}

  public List<Card> pending(AgentId agentId) {
    return waiting.values().stream()
        .filter(question -> question.agentId().equals(agentId))
        .sorted(java.util.Comparator.comparing(Waiting::askedAt))
        .map(ApprovalDesk::render)
        .toList();
  }

  public Optional<Card> card(CallId callId) {
    return Optional.ofNullable(waiting.get(callId)).map(ApprovalDesk::render);
  }

  public Optional<Waiting> take(CallId callId) {
    return Optional.ofNullable(waiting.remove(callId));
  }

  private static Card render(Waiting question) {
    return new Card(
        question.callId().value(),
        question.tool(),
        question.arguments(),
        question.description(),
        question.askedAt());
  }
}
