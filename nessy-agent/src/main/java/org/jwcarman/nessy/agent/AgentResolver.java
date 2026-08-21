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
package org.jwcarman.nessy.agent;

/**
 * Binds a scope coordinate to its live instance (spec §4.3 amendment, extending the binder
 * principle): whoever implements this provably holds — or can construct — the scope's {@link
 * DefaultAgent}, exactly as {@link AgentBinder} does for ordinary delivery.
 */
@FunctionalInterface
public interface AgentResolver {

  DefaultAgent<?> resolve(AgentType type, AgentId id);
}
