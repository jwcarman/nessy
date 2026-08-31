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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.springframework.stereotype.Component;

/**
 * Where the questions wait, and what the page reads.
 *
 * <p>In memory, and honestly so: this example's whole world is one process, and a desk that
 * outlived it would be claiming a durability the rest of the example does not have. The watchman
 * example puts the same projection in Postgres, which is what a real deployment does — the shape of
 * what is stored is identical either way, which is the point worth taking from this file.
 *
 * <p><b>The token never reaches the browser.</b> It is the authority to settle a tool call, so the
 * page addresses a question by its call id and the desk looks the token up. A page that held tokens
 * would be a page that could be pasted into a chat window.
 */
@Component
public class ApprovalDesk {

  /** One question, as both the page and the answer path need it. */
  public record Waiting(
      String agentId,
      String callId,
      String tool,
      String arguments,
      String description,
      Instant askedAt,
      ReplyToken replyToken) {}

  private final ConcurrentMap<String, Waiting> waiting = new ConcurrentHashMap<>();

  /** Records a question the approver has just deferred. */
  public void expecting(ApprovalRequest request, ReplyToken replyToken) {
    waiting.put(
        request.call().id(),
        new Waiting(
            request.agentId().value(),
            request.call().id(),
            request.call().name(),
            request.call().arguments().toPrettyString(),
            request.description(),
            request.askedAt(),
            replyToken));
  }

  /** Everything still waiting on this agent, oldest first. */
  public List<Map<String, ?>> pending(String agentId) {
    return waiting.values().stream()
        .filter(question -> question.agentId().equals(agentId))
        .sorted(java.util.Comparator.comparing(Waiting::askedAt))
        .map(ApprovalDesk::render)
        .toList();
  }

  /** One question as the page draws it — no token. */
  public Map<String, ?> card(String callId) {
    Waiting question = waiting.get(callId);
    return question == null ? Map.of("id", callId) : render(question);
  }

  /**
   * Takes a question off the desk, if it is still there.
   *
   * <p>Removing and answering are one step on purpose: two people with the page open both click,
   * and only the click that actually took the question gets to answer it. The loser is told the
   * question is gone rather than being allowed to settle a call twice.
   */
  public Optional<Waiting> take(String callId) {
    return Optional.ofNullable(waiting.remove(callId));
  }

  private static Map<String, ?> render(Waiting question) {
    return Map.of(
        "id", question.callId(),
        "tool", question.tool(),
        "args", question.arguments(),
        "what", question.description(),
        "askedAt", question.askedAt().toString());
  }
}
