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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.inference.tool.CallId;
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

  /**
   * What a blocked turn is waiting on, per question.
   *
   * <p>The difference between a desk that parks a turn and one that holds it. On the queued door
   * the engine writes the question down and comes back for the answer whenever it arrives; here the
   * turn is on somebody's request thread and the answer has to reach it there.
   */
  private final ConcurrentMap<CallId, CompletableFuture<ApprovalResult>> answers =
      new ConcurrentHashMap<>();

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

  /**
   * Waits for a person to answer this question.
   *
   * <p>Blocks the turn, which is what the direct door means: the caller is holding the answer, so
   * the approval is part of what it is holding. Bounded, because a request thread waiting forever
   * on somebody who closed the tab is a thread nobody gets back -- and a question nobody answered
   * is a no, which is the safe direction for a gate to fail in.
   */
  public ApprovalResult await(CallId callId, Duration patience) {
    CompletableFuture<ApprovalResult> answer =
        answers.computeIfAbsent(callId, _ -> new CompletableFuture<>());
    try {
      return answer.get(patience.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException _) {
      return ApprovalResult.denied("nobody answered within " + patience);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return ApprovalResult.denied("the wait was interrupted");
    } catch (ExecutionException broken) {
      return ApprovalResult.denied("the desk failed: " + broken.getMessage());
    } finally {
      answers.remove(callId);
      waiting.remove(callId);
    }
  }

  /** A person answered. Wakes whatever turn is waiting on it; harmless if nothing is. */
  public boolean answer(CallId callId, ApprovalResult result) {
    CompletableFuture<ApprovalResult> answer =
        answers.computeIfAbsent(callId, _ -> new CompletableFuture<>());
    return answer.complete(result);
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
