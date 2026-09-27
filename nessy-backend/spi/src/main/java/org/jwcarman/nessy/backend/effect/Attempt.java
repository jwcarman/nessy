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
package org.jwcarman.nessy.backend.effect;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;

/**
 * One claimed effect row, as handed to whoever is about to perform it.
 *
 * <p>Carries both blobs unread. Decoding is {@link Effects#effectOf} and {@link Effects#failureOf},
 * called separately and only when wanted, because the two are independent: a row whose effect
 * cannot be read can still say what to tell the waiting agent.
 *
 * <p>{@code deadline} is fixed by the first claim and read back by every one after it, so it is
 * settled by the time anyone holds an attempt -- which is why it is not optional here even though
 * the column is. Nothing this attempt does may be scheduled past it: a retry whose backoff would
 * land beyond it is not a later retry, it is a give-up.
 */
public record Attempt(
    UUID effectId,
    AgentId agentId,
    byte[] payload,
    byte[] failurePayload,
    int attemptsMade,
    Instant deadline,
    /** The trace this effect was emitted in, or null if nothing was tracing. */
    String traceContext,
    /**
     * A store's private carrier for what earlier attempts learned. Encoded the same way the two
     * blobs above are, and for the same reason: what a failed attempt knows is the engine's
     * vocabulary, and a row that understood it would have to change when that does.
     *
     * <p><b>Read it through {@link Effects#attemptsOf}, never directly.</b> Only a store that keeps
     * bytes puts anything here -- one holding objects has nowhere to encode to and leaves it null
     * while keeping the attempts itself -- so a caller reaching for this field works against one
     * implementation and silently sees nothing on the other.
     */
    byte[] failedAttempts) {

  // Every blob is compared by content, as a record of arrays otherwise would not be.

  @Override
  public boolean equals(Object o) {
    return o
            instanceof
            Attempt(
                UUID thatEffectId,
                AgentId thatAgentId,
                byte[] thatPayload,
                byte[] thatFailurePayload,
                int thatAttemptsMade,
                Instant thatDeadline,
                String thatTraceContext,
                byte[] thatFailedAttempts)
        && attemptsMade == thatAttemptsMade
        && Objects.equals(effectId, thatEffectId)
        && Objects.equals(agentId, thatAgentId)
        && Arrays.equals(payload, thatPayload)
        && Arrays.equals(failurePayload, thatFailurePayload)
        && Objects.equals(deadline, thatDeadline)
        && Objects.equals(traceContext, thatTraceContext)
        && Arrays.equals(failedAttempts, thatFailedAttempts);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        effectId,
        agentId,
        Arrays.hashCode(payload),
        Arrays.hashCode(failurePayload),
        attemptsMade,
        deadline,
        traceContext,
        Arrays.hashCode(failedAttempts));
  }

  @Override
  public String toString() {
    return "Attempt[effectId=%s, agentId=%s, payload=%d bytes, failurePayload=%d bytes, attemptsMade=%d, deadline=%s, traceContext=%s, failedAttempts=%d bytes]"
        .formatted(
            effectId,
            agentId,
            payload == null ? 0 : payload.length,
            failurePayload == null ? 0 : failurePayload.length,
            attemptsMade,
            deadline,
            traceContext,
            failedAttempts == null ? 0 : failedAttempts.length);
  }
}
