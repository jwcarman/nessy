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
package org.jwcarman.nessy;

/**
 * The DSL-idiom name (design of record 2026-08-16 §1) for what {@link
 * Harness#agent(AgentCustomizer)} and {@link Harness#agent(Class, AgentCustomizer)} hand a lambda:
 * an {@link AgentConfig} to fill in. A named functional interface rather than a bare {@code
 * Consumer<AgentConfig<T>>} — matching {@link SubagentCustomizer}, {@link HarnessCustomizer}, and
 * every other named customizer in this codebase rather than borrowing {@code java.util.function}'s
 * generic shape.
 *
 * @param <T> the input vocabulary the customized agent accepts via {@code tell} — {@code String} on
 *     the everyday door, an application-owned record on the typed door
 */
@FunctionalInterface
public interface AgentCustomizer<T> {

  /** Fills in {@code agent} — the only thing a customizer ever does. */
  void customize(AgentConfig<T> agent);
}
