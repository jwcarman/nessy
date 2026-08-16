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
/**
 * The subagent facility's storage contract: {@link org.jwcarman.nessy.spi.subagent.SubagentLinks},
 * the durable parent-child correlation a settled child's completion wakes against — which parent
 * {@link org.jwcarman.nessy.api.ParkToken} a child conversation answers. {@link
 * org.jwcarman.nessy.spi.subagent.SubagentLinks#inMemory()} is the zero-configuration default; a
 * durable implementation (e.g. {@code JdbcSubagentLinks}) is what a harness supplies via {@link
 * org.jwcarman.nessy.HarnessBuilder#subagentLinks}.
 *
 * <p>The delegation tool and the wake-up listener that once lived here as public API (v1's {@code
 * AgentTools}, {@code CallbackRouter}) are internal machinery now, assembled by {@link
 * org.jwcarman.nessy.AgentBuilder#subagent} — see the design of record, 2026-08-16, for the full
 * construction surface. This package holds only the storage contract, mirroring how {@code
 * org.jwcarman.nessy.spi.plan} holds the plan facility's own store and {@code
 * org.jwcarman.nessy.spi.notebook} holds the notebook facility's.
 */
package org.jwcarman.nessy.spi.subagent;
