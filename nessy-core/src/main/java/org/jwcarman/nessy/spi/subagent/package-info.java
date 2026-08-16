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
 * The subagent facility: turning an {@link org.jwcarman.nessy.Agent} into an ordinary {@link
 * org.jwcarman.nessy.api.tool.Tool} ({@link org.jwcarman.nessy.spi.subagent.AgentTools}) and the
 * parent-child correlation a settlement wakes against ({@link
 * org.jwcarman.nessy.spi.subagent.SubagentLinks}), mirroring how {@code
 * org.jwcarman.nessy.spi.plan} holds the plan facility and {@code org.jwcarman.nessy.spi.notebook}
 * holds the notebook facility.
 */
package org.jwcarman.nessy.spi.subagent;
