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
package org.jwcarman.nessy.agent.spi;

import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * What a wiring does when a tool parks (spec §4.3). {@link ToolExecution.Deferred} means the call
 * is suspended into its durable slot: the executor delivers nothing and narrates nothing — parking
 * is invisible, but the suspension carries its reference. {@link ToolExecution.Immediate} means an
 * outcome to deliver now — the loud in-band failure of a non-parking wiring, or a durable slot's
 * already-terminal answer.
 */
@FunctionalInterface
public interface ParkedCallPolicy {

  ToolExecution onParked(ToolCall call, ParkToken token);
}
