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
package org.jwcarman.nessy.api;

import org.jwcarman.nessy.api.tool.Tool;

/**
 * What both doors' configurations have in common: which agent this is, and what it can reach.
 *
 * <p><b>Why it is separate from either door's config.</b> Something that equips an agent -- a
 * memory module putting its tools and its index in front of the model, say -- has nothing to say
 * about what an agent is told or what it answers with. It needs an agent type to key its storage
 * on, a way to add tools, and a way to add what the model sees every turn. Everything else on a
 * harness config is the application's business, and a jar on the classpath has no opinion about it.
 *
 * <p><b>One of these serves both doors.</b> A factory could keep customizers of its own door's
 * config instead -- {@code Customizer<DirectHarnessConfig<?, ?>>} stores and applies perfectly well
 * -- but then whatever equips an agent has to be written twice, once per door, and gets the whole
 * configuration surface while it is there. This is narrower on purpose: it can be applied to either
 * door's config, and it can only do the things equipping an agent means.
 *
 * <p>The self type is what keeps chaining intact through a wildcard: each call returns the captured
 * type, which is a {@code HarnessConfig} again, so {@code x.tool(a).ambient(b)} reads as it should.
 *
 * <p><b>A capability, not a universal.</b> This is what it takes to EQUIP an agent -- to give it
 * something to call and something to read. A door that cannot be equipped does not implement it,
 * and something that equips agents then correctly never reaches that door. Nothing here claims
 * every harness that will ever exist has these.
 *
 * <p><b>Neither door redeclares these.</b> That is the point of the self type rather than a
 * covariant override: {@code config.tool(t).systemPrompt("...")} keeps returning the door's own
 * config, so an application never notices this interface exists.
 *
 * @param <SELF> the configuration this is part of, so every call returns it
 */
public interface HarnessConfig<SELF extends HarnessConfig<SELF>> {

  /**
   * Which agent this harness serves.
   *
   * <p>Settled when the harness was asked for, never here: an agent type has no possible default,
   * and a wrong one writes to another agent's rows. Something equipping an agent reads it to key
   * whatever it keeps -- a notebook and a plan are per agent type, not per process.
   */
  AgentType agentType();

  /**
   * What bounds a turn that will not finish.
   *
   * <p>Defaults to {@link TurnPolicy#calls(int, int)} at twenty and twenty-five: the model is asked
   * to answer from what it has at twenty calls, and the turn ends at twenty-five. A default is here
   * at all because doing nothing is not the safe choice -- unlike a retry policy, where doing
   * nothing costs nothing, an unbounded turn that will not converge spends until somebody notices.
   *
   * <p>On both doors, because both fold the same way and neither has a reason to differ.
   */
  SELF turnPolicy(TurnPolicy policy);

  /** Something the model may call. */
  <T> SELF tool(Tool<T> tool);

  /**
   * Something the model is shown every turn, asked afresh each time.
   *
   * <p>Never written into the story, so what it returns is what is true now rather than what was
   * true when a turn began.
   */
  SELF ambient(AmbientSource source);
}
