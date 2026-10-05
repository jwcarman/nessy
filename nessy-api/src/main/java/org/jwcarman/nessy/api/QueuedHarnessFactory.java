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

import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.tool.Replies;

/**
 * Makes harnesses, holding the infrastructure every agent type is built from.
 *
 * <p>The split is deliberate: a caller supplies what is theirs -- the agent type's name, what it
 * is, how to read its inputs -- and this supplies the stores, the transaction template, the
 * scheduler, the codec factory, and the provider and model to use when an agent type does not care
 * to choose. Nobody assembles a harness by hand, so nobody can assemble one wrongly.
 *
 * <p><b>Shared infrastructure, not shared machinery.</b> Two harnesses use the same tables and the
 * same scheduler the way they use the same JVM. Nothing else crosses between them: each gets its
 * own codec, its own model, its own dispatcher, its own schedule and its own rows.
 */
public interface QueuedHarnessFactory extends AutoCloseable {

  /** For inputs that are already what a model should read. */
  default QueuedHarness<String> create(
      AgentType agentType, Customizer<QueuedHarnessConfig<String>> customizer) {
    return create(agentType, String.class, customizer);
  }

  /** For an input type that is not itself generic, which is nearly all of them. */
  default <I> QueuedHarness<I> create(
      AgentType agentType, Class<I> inputType, Customizer<QueuedHarnessConfig<I>> customizer) {
    return create(agentType, TypeRef.of(inputType), customizer);
  }

  /**
   * The general form, for an input type that is itself generic.
   *
   * <p>{@link TypeRef#parameterized} is why this exists: a {@code TypeRef} cannot be captured for a
   * type variable, so the agent's own codec has to be composed from the caller's, and only a {@code
   * TypeRef} can carry that through.
   */
  <I> QueuedHarness<I> create(
      AgentType agentType, TypeRef<I> inputType, Customizer<QueuedHarnessConfig<I>> customizer);

  /**
   * Where a late answer comes back in. One for the whole factory rather than one per harness: a
   * reply token is opaque, so whoever holds one cannot say which kind of agent it belongs to.
   */
  Replies replies();

  /** What the agents of this factory are doing, read from what is stored about them. */
  AgentWork work();

  /**
   * Stops looking for work.
   *
   * <p>On the interface because an application that built one has to be able to stop it: this owns
   * a timer and the threads that perform effects, which outlive the calls that submitted them. The
   * direct door has no equivalent and is deliberately not closeable -- a turn there ends when
   * {@code ask} returns, and there is nothing left running to shut down.
   *
   * <p>Declared without a checked exception, so a try-with-resources over one does not have to
   * catch something that cannot happen.
   */
  @Override
  void close();
}
