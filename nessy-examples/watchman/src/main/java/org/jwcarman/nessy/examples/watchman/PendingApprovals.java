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
package org.jwcarman.nessy.examples.watchman;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * The approvals page's read side: what is waiting on a person, and where to answer it.
 *
 * <p><b>The choice, and why it changed.</b> This used to read the write model — scan {@code
 * durable_state}, deserialise each agent, and ask which of its calls were parked. That worked
 * because the agent persisted everything the page needed. It no longer does: an agent persists its
 * backlog and which turn is running, a turn persists its phase, and the actor waiting on a human is
 * ephemeral. "What is pending" stopped being a pure function of anything on disk.
 *
 * <p>So this is the projection the old design deliberately rejected — with the difference that it
 * is now fed by {@link AgentEvent} rather than by a second write the application makes by hand.
 * Nothing here reaches into the engine: an approver records where an answer goes, {@link
 * AgentEvent.ApprovalRequested} says what was asked, and {@link AgentEvent.ApprovalDecided} clears
 * it. That is all public API.
 *
 * <p><b>It survives a restart</b>, which is the part that looks like it should not. A recovered
 * turn re-runs the calls it never settled, which spawns the approval again, which narrates {@link
 * AgentEvent.ApprovalRequested} again — so a page that came up empty fills itself back in as the
 * agents recover, rather than needing its own durable copy.
 *
 * <p><b>What it costs.</b> The projection is per-process and in memory: it holds what THIS
 * application has heard. Two instances serving the same page would each see only their own
 * subscriptions, and the answer then is a shared store — at which point the drift the old design
 * feared comes back and has to be managed.
 */
public final class PendingApprovals implements AgentSubscriber {

  /** One waiting approval, as the page needs it. */
  public record Row(
      String agentId,
      String callId,
      String tool,
      String action,
      Instant askedAt,
      Instant expiresAt,
      String dwell) {}

  private record Waiting(
      String agentId, String callId, String tool, String action, Instant askedAt,
      Instant expiresAt, ReplyToken replyTo) {}

  private final Map<String, Waiting> byCallId = new ConcurrentHashMap<>();
  private final Map<String, ReplyToken> addresses = new ConcurrentHashMap<>();
  private final Clock clock;
  private final String agentId;

  public PendingApprovals(Clock clock, String agentId) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
  }

  /**
   * Where an answer for {@code callId} goes, recorded by the approver at the moment it decided a
   * person was needed.
   *
   * <p>Separate from {@link #on(AgentEvent)} because the two arrive by different routes and neither
   * waits for the other: the approver knows the address, the narration knows the question, and a
   * row is only offered once both have landed.
   */
  public void awaiting(String callId, ReplyToken replyTo) {
    addresses.put(callId, replyTo);
  }

  @Override
  public void on(AgentEvent event) {
    switch (event) {
      case AgentEvent.ApprovalRequested asked ->
          addresses.computeIfPresent(
              asked.callId(),
              (callId, replyTo) -> {
                byCallId.put(
                    callId,
                    new Waiting(
                        agentId,
                        callId,
                        asked.toolName(),
                        asked.description(),
                        clock.instant(),
                        asked.expiresAt(),
                        replyTo));
                return replyTo;
              });
      case AgentEvent.ApprovalDecided decided -> forget(decided.callId());
      default -> {
        // Every other event is somebody else's business.
      }
    }
  }

  /** Everything still waiting, oldest first. */
  public List<Row> pending() {
    Instant now = clock.instant();
    return byCallId.values().stream()
        .sorted(Comparator.comparing(Waiting::askedAt))
        .map(
            waiting ->
                new Row(
                    waiting.agentId(),
                    waiting.callId(),
                    waiting.tool(),
                    waiting.action(),
                    waiting.askedAt(),
                    waiting.expiresAt(),
                    dwell(Duration.between(waiting.askedAt(), now))))
        .toList();
  }

  /** Where to send an answer for {@code callId}, if it is still waiting for one. */
  public Optional<ReplyToken> addressOf(String callId) {
    return Optional.ofNullable(byCallId.get(callId)).map(Waiting::replyTo);
  }

  /** Drops a call, answered or expired. Idempotent: a second answer finds nothing to forget. */
  public void forget(String callId) {
    byCallId.remove(callId);
    addresses.remove(callId);
  }

  private static String dwell(Duration waited) {
    long minutes = Math.max(0, waited.toMinutes());
    if (minutes < 60) {
      return minutes + "m";
    }
    return (minutes / 60) + "h " + (minutes % 60) + "m";
  }
}
