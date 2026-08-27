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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.time.Instant;

/**
 * One tool call, as the AGENT persists it.
 *
 * <p><b>Read this beside the spike's version and you can see where the round-3 collapse held and
 * where it did not.</b> The spike got this down to {@code (id, tool, argument, outcome)} — the
 * lifecycle lived entirely in {@link ToolCallActor}'s behaviour, and nothing about approval was
 * written down. Two of the watchman's real requirements pushed fields back in, and neither is a
 * state machine:
 *
 * <ul>
 *   <li>{@code decision} — because a human's answer must survive a crash. An answer relayed to a
 *       live child and never persisted is lost if the JVM dies a millisecond later, and "we told
 *       the operator 200 OK and then forgot" is the one failure this application may not have.
 *   <li>{@code askedAt} — because the approvals page shows dwell time, and no fact in an actor's
 *       mailbox has a timestamp anyone can read later.
 * </ul>
 *
 * <p>What did NOT come back is the state machine: there is still no {@code AwaitingApproval |
 * Running | Denied} enumeration, no admission matrix and no per-variant re-fire rule. The
 * distinction that matters is between <b>facts arriving from outside</b>, which must be persisted,
 * and <b>transitions the machine drives itself</b>, which need not be.
 *
 * @param decision null until a human has answered; an approval-free tool never gets one
 * @param outcome null while the call is in flight
 */
public record ToolCallRecord(
    String id,
    String tool,
    String argumentsJson,
    String action,
    Instant askedAt,
    Decision decision,
    String outcome) {

  /** A human's answer, and who gave it. Persisted, because it cannot be reconstructed. */
  public record Decision(boolean approved, String by, String note, Instant at) {}

  public static ToolCallRecord asked(
      String id, String tool, String argumentsJson, String action, Instant now) {
    return new ToolCallRecord(id, tool, argumentsJson, action, now, null, null);
  }

  public boolean settled() {
    return outcome != null;
  }

  public boolean decided() {
    return decision != null;
  }

  public ToolCallRecord decidedBy(Decision made) {
    return new ToolCallRecord(id, tool, argumentsJson, action, askedAt, made, outcome);
  }

  public ToolCallRecord settledWith(String result) {
    return new ToolCallRecord(id, tool, argumentsJson, action, askedAt, decision, result);
  }
}
