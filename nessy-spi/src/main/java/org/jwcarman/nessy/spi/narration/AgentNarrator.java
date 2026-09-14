package org.jwcarman.nessy.spi.narration;

import org.jwcarman.nessy.api.AgentEvent;

/**
 * A {@link Narrator} that already knows whose story it is telling.
 *
 * <p>Handed to anything that says what happened without being allowed to know who it happened to. A
 * provider is the case that matters: {@link org.jwcarman.nessy.spi.inference.InferenceRequest}
 * carries no agent identity on purpose -- "a provider that could see it could read something it has
 * no business reading" -- and a narrator taking an agent argument would hand that straight back.
 *
 * <p>Same promises as the narrator behind it: best-effort, never durable, and safe to call from
 * whatever thread is doing the work.
 */
@FunctionalInterface
public interface AgentNarrator {

  void narrate(AgentEvent event);

  /** Nobody is listening. */
  static AgentNarrator silent() {
    return _ -> {};
  }
}
