package org.jwcarman.nessy.engine.inference;

import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * One inference on an agent's behalf: read its story, choose what to send, send it.
 *
 * <p>The agent-facing half. It knows agents and history and nothing about a provider's protocol,
 * exactly as {@link InferenceProvider} knows the protocol and nothing about agents. Everything that
 * holds both sides at once is in one place, and it is three lines long.
 */
@FunctionalInterface
public interface InferenceService {

  InferenceResult infer(InferenceInvocation invocation);
}
