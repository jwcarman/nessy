package org.jwcarman.nessy.api;

import java.time.Duration;

/**
 * How this agent type performs the work it owes itself -- across every kind of effect.
 *
 * <p>Separate from {@code inference(...)} and {@code tool(...)} because these are not per kind. One
 * dispatcher serves an agent type, polls for everything it owes, and bounds all of it together.
 * What each kind of work is worth belongs with that kind; how briskly and how much at once belongs
 * here.
 *
 * <p>Per agent type rather than global: an agent type answering a person wants a short interval,
 * one grinding through overnight work does not, and neither should have to live with the other's
 * choice.
 */
public interface EffectsConfig {

  /** How often to look for work that has become due. */
  EffectsConfig pollInterval(Duration pollInterval);

  /**
   * How much work this agent type may have running at once, of any kind.
   *
   * <p>Also the size of any one batch: a pass takes as many rows as it has spare capacity for, so a
   * saturated dispatcher takes none and an idle one takes the lot.
   *
   * <p>It bounds the agent type, not the effect kind -- so once tools exist, slow tool calls can
   * occupy the budget and hold up inferences for every agent of this type. That is a resource bound
   * behaving correctly, and it is also the likeliest explanation the next time an agent type goes
   * quiet for no visible reason.
   */
  EffectsConfig maxInFlight(int maxInFlight);
}
