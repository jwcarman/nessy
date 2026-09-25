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
 * Fills in a configuration before whatever it describes is built.
 *
 * <p>One type for every configurable thing in Nessy, rather than a named interface per config. What
 * is being configured is the type argument, so {@code Customizer<DirectHarnessConfig<String>>} says
 * what it does without a name having to.
 *
 * <p><b>Ours rather than {@link java.util.function.Consumer}, and that is the whole reason it
 * exists.</b> A consumer of a config is indistinguishable from any other consumer of the same type,
 * which matters the moment somebody wants to find every customizer of a thing -- a container
 * collecting them as beans, say. A type of our own is something that can be asked for.
 *
 * <p><b>Void, because a config is a builder.</b> Every config here is mutated in place and returns
 * itself for chaining, so a returned value would be ignored at every call site and would invite an
 * immutable config the others do not match.
 *
 * @param <C> the configuration this fills in
 */
@FunctionalInterface
public interface Customizer<C> {

  /**
   * Fills in {@code configurable}.
   *
   * <p>Called once, before the thing being configured exists. What a customizer does not set keeps
   * whatever default the config declares.
   */
  void customize(C configurable);

  /** Changes nothing, for a caller who wants what the config already says. */
  static <C> Customizer<C> withDefaults() {
    return _ -> {};
  }
}
