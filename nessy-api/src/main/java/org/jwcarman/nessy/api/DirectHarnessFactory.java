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

/**
 * Makes direct harnesses that share what an application owns once.
 *
 * <p>Where events are written, where content is kept, who provides inference, and what keeps two
 * callers off one agent are settled here; a harness adds what is its own -- a type, a prompt, its
 * tools, what it is shown.
 *
 * <p><b>Nothing here is closeable</b>, which is the difference from {@link QueuedHarnessFactory}
 * worth noticing. That one owns a timer and every harness it made, because work outlives the call
 * that submitted it. Here a turn ends when {@code ask} returns and there is nothing left running.
 */
public interface DirectHarnessFactory {

  /**
   * A harness for one kind of agent.
   *
   * @param <I> what a caller hands in. Unlike the queued door there is no type token: nothing here
   *     is written down as {@code I}, so nothing needs a codec for it. What is stored is what the
   *     renderer made of it.
   */
  <I> DirectHarness<I> create(AgentType agentType, Customizer<DirectHarnessConfig<I>> customizer);

  /** The vendor behind this, as the OpenTelemetry GenAI conventions name it. */
  String providerName();
}
