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
package org.jwcarman.nessy.api.tool;

/**
 * The typed tier: a {@link Tool} whose {@link #effect(Object)} statement is a specific type {@code
 * E} rather than the untyped {@link Object} every {@link Tool} settles for.
 *
 * <p>{@code E} welds through to a grant's {@link UsagePolicy} at compile time — a mismatch between
 * the tool's own effect type and the policy that judges it does not compile. The effect is still
 * rendered exactly once per evaluated call and still reaches the approval prompt via its own {@code
 * toString()}, the same as any {@link Tool}; typing it only sharpens what enrichers and policies
 * can see and assert about without casting.
 *
 * @param <T> the record this tool's arguments arrive in
 * @param <E> the type of this tool's own effect statement
 */
public interface EffectfulTool<T, E> extends Tool<T> {

  @Override
  E effect(T input);
}
