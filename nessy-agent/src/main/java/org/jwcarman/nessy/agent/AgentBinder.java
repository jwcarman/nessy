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
 * The out-of-band delivery door (spec §4.3): whoever implements this provably holds — or can
 * construct — the scope's instance, and hands the event through its package-private door. Hosts
 * implement it; a binder that builds a fresh instance per delivery is the transient-instance model
 * working as designed.
 */
@FunctionalInterface
public interface AgentBinder {

  void deliver(AgentType type, AgentId id, AgentEvent event);
}
