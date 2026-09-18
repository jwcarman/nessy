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
package org.jwcarman.nessy.engine.effect;

import java.time.Duration;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.engine.agent.EffectOutcome;

/**
 * What one kind of effect is worth, for one agent type.
 *
 * <p>Separate from {@link EffectHandler} and without its type parameter, because the two are
 * consulted at different moments by different code. The fold asks for terms while writing a row and
 * has not decoded anything, so it cannot know the kind statically; the dispatcher asks for the work
 * once it has.
 *
 * <p>Not all of this is read the same way, and the rule is mechanical rather than philosophical:
 * <b>what SQL touches is frozen onto the row, what only Java touches is read live.</b> {@link
 * #timeout()} and {@link #undispatchable()} are written at insert -- the claim query caps {@code
 * actionable_at} against the deadline before anything is decoded, and the fallback outcome has to
 * be readable precisely when decoding failed. {@link #retryPolicy()} is only ever consulted after a
 * decode, so it is asked for then and no copy is stored.
 */
public interface EffectTerms {

  /**
   * How long the agent is willing to wait, measured from when the effect is written down. Queueing
   * included: time waiting to be picked up is time the agent spent waiting.
   */
  Duration timeout();

  /**
   * How hard a failed attempt is worth repeating.
   *
   * <p>Its own per kind of effect: an inference that never reached a provider changed nothing and
   * is worth repeating, while a tool call whose outcome was never observed may well have run.
   */
  RetryPolicy retryPolicy();

  /**
   * What to tell the agent when the effect can never be performed at all -- a payload this build
   * cannot read, or a deadline that passed before anyone picked the work up.
   *
   * <p>Frozen beside the row in its own blob, so that a payload which will not decode does not take
   * the handling of that failure down with it.
   */
  EffectOutcome undispatchable();

  /**
   * What to tell the agent when the work was attempted, threw, and will not be attempted again.
   *
   * <p>Distinct from {@link #undispatchable()} because something is actually known here: an attempt
   * ran and there is an exception describing how it went wrong. Falling back to the stored blob
   * would throw that away and tell the agent the effect could not be dispatched, which is false --
   * it was dispatched, and it failed.
   *
   * <p>Nobody found out whether the work happened, though. A call that failed on its own terms
   * never reaches here; the handler classifies those and returns them as outcomes. What lands here
   * is anything else that threw, so the honest classification is unknown.
   */
  EffectOutcome failed(RuntimeException cause);
}
