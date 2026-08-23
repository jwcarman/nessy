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
package org.jwcarman.nessy.agent.host;

/**
 * The DSL-idiom name (design of record 2026-08-16 §1, harness-first spec §2) for what {@link
 * Nessy#harness(HarnessCustomizer)}/{@link Nessy#harness(Class, HarnessCustomizer)} hand a lambda:
 * a {@link HarnessConfig} to fill in. A named functional interface rather than a bare {@code
 * Consumer<HarnessConfig<O>>} — mirroring {@link org.jwcarman.nessy.api.tool.ToolCustomizer} and
 * every other named customizer in this codebase, one lesson for both.
 *
 * @param <O> the observation type the built harness accepts
 */
@FunctionalInterface
public interface HarnessCustomizer<O> {

  /** Fills in {@code harness} — the only thing a customizer ever does. */
  void customize(HarnessConfig<O> harness);
}
