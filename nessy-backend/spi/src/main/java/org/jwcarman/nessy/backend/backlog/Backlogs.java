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
package org.jwcarman.nessy.backend.backlog;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * One agent's backlog, made on demand.
 *
 * <p>The store behind it is shared by every agent of every type; a {@link Backlog} is one agent's
 * view of it, which is why this exists rather than the harness holding the store and passing a
 * {@code (type, agent)} pair into every call. The same shape as {@code Payloads.forAgent} and
 * {@code Outbox}, and for the same reason: the harness already knows whose backlog it wants, and
 * saying so once is better than saying it at every use.
 *
 * <p><b>Called inside the transaction that holds the agent's row.</b> A backlog handed out here is
 * read and written under that lock, so an implementation must not do anything slow to produce one
 * -- it is a view, not a load.
 *
 * @param <I> what a caller hands in
 */
@FunctionalInterface
public interface Backlogs<I> {

  Backlog<I> forAgent(AgentType agentType, AgentId agent);
}
