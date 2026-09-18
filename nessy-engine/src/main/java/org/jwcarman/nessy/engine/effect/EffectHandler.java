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

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;

/**
 * Does one kind of effect, and says what that kind is worth.
 *
 * <p>Both, on purpose. There used to be two registries keyed by the same thing -- a lookup for
 * terms and a switch for who does the work -- and with one kind of effect that is invisible. With
 * three it is two lists that can disagree: a kind with terms and no handler is claimed and then
 * dropped, one with a handler and no terms cannot be written down at all. Keeping them together
 * makes both halves arrive at once or not compile.
 *
 * <p>Everything a handler needs to do the work it holds itself, from construction -- which model to
 * call, which tool to run. None of that is written to the row, so a queued effect performed
 * tomorrow uses tomorrow's configuration. That is deliberate: switching a model to route around an
 * outage should reach the backlog, not just the work that arrives after it.
 *
 * @param <E> the kind of effect this handles
 */
public interface EffectHandler<E extends AgentEffect> {

  /**
   * What a call of this effect is worth.
   *
   * <p>Takes the effect, because the terms are not always a property of the kind. Every inference
   * of one agent type is worth the same; every tool call is not, and an application says so per
   * tool -- thirty seconds for a lookup, five minutes for a build. A handler whose terms are
   * uniform ignores the argument and returns itself.
   *
   * <p>Consulted while the row is being written, so whatever this reads must be in hand already.
   * That is why {@link AgentEffect.CallTool} carries the tool's name.
   */
  EffectTerms termsFor(E effect);

  /**
   * Performs it, and says what came of it -- or that somebody else will.
   *
   * <p>Returns an outcome rather than throwing for anything it understands. A throw reaches the
   * retry decision as {@code Failure.Unknown}, which is the honest answer only when nobody found
   * out whether the work happened.
   *
   * <p><b>{@link Awaited.Deferred} means the work is genuinely elsewhere</b> -- a person has been
   * asked, a queue has the job -- and an answer will arrive later against a {@link
   * org.jwcarman.nessy.api.tool.ReplyToken}. It is not "try again later": the effect has been
   * performed, in the only sense that matters, and repeating it would ask twice.
   *
   * <p>Uniform even for effects that can never defer. An inference either answers or does not, and
   * there is nobody to come back afterwards -- so {@link InferenceHandler} wraps every return in
   * {@link Awaited#ready}. One handler shape is worth three wrappers: the alternative is two
   * interfaces and a dispatcher that has to know which kind it is holding.
   */
  Awaited<EffectOutcome> handle(AgentId agentId, E effect);
}
