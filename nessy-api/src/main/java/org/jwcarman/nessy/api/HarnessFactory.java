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

import java.util.function.Consumer;

/**
 * Builds harnesses — one per kind of agent.
 *
 * <p>The infrastructure is given to an implementation ONCE, at construction: where state lives, how
 * models are reached, what runs the work. Each {@link #createHarness} call adds only what makes one
 * kind of agent different from another, which is why {@link HarnessConfig} names no infrastructure
 * at all.
 *
 * <p><b>The observation type must round-trip through JSON.</b> An agent's waiting backlog is part
 * of its persisted state, so an observation is stored as an observation — not as a rendered message
 * — and read back after a restart. Two things follow for an application's vocabulary:
 *
 * <ul>
 *   <li>A SEALED vocabulary carries its own standard Jackson {@code @JsonTypeInfo} /
 *       {@code @JsonSubTypes} annotations, exactly as this API's own sealed hierarchies do. Nothing
 *       here infers a discriminator on an application's behalf.
 *   <li>Those discriminator values are a COMPATIBILITY SURFACE. They are written into stored
 *       backlogs, so renaming one orphans every observation already waiting. Choose them boringly
 *       and leave them alone — the same rule this API follows for {@code tool-use} and friends.
 * </ul>
 *
 * <p>{@code observationType} is load-bearing rather than ceremony: erasure means the runtime needs
 * a class literal to key its own machinery on, and whatever stores a waiting backlog needs the type
 * to encode what it holds.
 */
public interface HarnessFactory {

  <O> Harness<O> createHarness(Class<O> observationType, Consumer<HarnessConfig<O>> configurer);
}
