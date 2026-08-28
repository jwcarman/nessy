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

import org.jwcarman.nessy.api.agent.AgentType;

/**
 * Binds a scope coordinate to its live instance (spec §4.3 amendment, extending the binder
 * principle): whoever implements this provably holds — or can construct — the scope's {@link
 * Agent}, exactly as {@link AgentBinder} does for ordinary delivery. Returns the application-facing
 * {@link Agent} type (harness-first spec §4, the Binding demotion) rather than the concrete {@link
 * DefaultAgent} — its one concrete-type dependency dissolved once {@link Harness#bind(AgentId)}
 * became the only door callers (including white-box test fixtures) need.
 *
 * <p>The returned {@link Agent} MUST be one Nessy's own doors produced — in practice always a
 * {@link DefaultAgent} handed back by {@link Harness#bind(AgentId)}. A foreign {@link Agent}
 * implementation is not a supported answer: {@link ResolvingAgentBinder}, the one consumer of this
 * resolver, rejects it with an {@link IllegalStateException} the instant it tries to unwrap one
 * that is not a {@code DefaultAgent}.
 */
@FunctionalInterface
public interface AgentResolver {

  Agent<?> resolve(AgentType type, AgentId id);
}
