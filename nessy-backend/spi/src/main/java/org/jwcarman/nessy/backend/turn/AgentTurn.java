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
package org.jwcarman.nessy.backend.turn;

import java.time.Instant;
import java.util.Objects;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;

/**
 * One completed turn, summarised: its trajectory, how it ended, and the counts a question about
 * behaviour asks first. A projection of the turn's events, written when the turn ends.
 *
 * <p>{@code turn} is the seq of {@code TurnStarted} and {@code endingSeq} the seq of the event that
 * ended the turn, so the two bound the turn's slice of the event stream, inclusive. Re-folding that
 * slice reproduces the trajectory, which is how a stored hash is audited.
 *
 * @param trajectoryJson the trajectory as JSON (spec §4.6): readable, and one-to-one with the hash
 *     under its version. Equal as JSON, not as text: a store may normalise it (Postgres reorders
 *     keys and adds spaces).
 * @param arrivedAt when the input reached the harness
 * @param startedAt when the turn opened
 * @param endedAt when the ending event was written
 * @param inferenceCalls every model call made, retries included
 * @param inferenceRetries the attempts that failed and were tried again
 */
public record AgentTurn(
    TurnId turn,
    Seq endingSeq,
    Instant arrivedAt,
    Instant startedAt,
    Instant endedAt,
    Trajectory trajectory,
    String trajectoryJson,
    TurnOutcome outcome,
    int rounds,
    int toolCalls,
    int toolSuccesses,
    int toolFailures,
    int toolDenials,
    int inferenceCalls,
    int inferenceRetries) {

  public AgentTurn {
    Objects.requireNonNull(turn, "turn must not be null");
    Objects.requireNonNull(endingSeq, "endingSeq must not be null");
    Objects.requireNonNull(arrivedAt, "arrivedAt must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    Objects.requireNonNull(endedAt, "endedAt must not be null");
    Objects.requireNonNull(trajectory, "trajectory must not be null");
    Objects.requireNonNull(trajectoryJson, "trajectoryJson must not be null");
    if (trajectoryJson.isBlank()) {
      throw new IllegalArgumentException("trajectoryJson must not be blank");
    }
    Objects.requireNonNull(outcome, "outcome must not be null");
    if (toolCalls != toolSuccesses + toolFailures + toolDenials) {
      throw new IllegalArgumentException("every tool call succeeded, failed or was denied");
    }
  }
}
