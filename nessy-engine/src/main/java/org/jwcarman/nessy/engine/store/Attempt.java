package org.jwcarman.nessy.engine.store;

import java.time.Instant;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;

/**
 * One claimed effect row, as handed to whoever is about to perform it.
 *
 * <p>Carries both blobs unread. Decoding is {@link EffectStore#effectOf} and {@link
 * EffectStore#failureOf}, called separately and only when wanted, because the two are independent:
 * a row whose effect cannot be read can still say what to tell the waiting agent.
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
    String traceContext) {}
