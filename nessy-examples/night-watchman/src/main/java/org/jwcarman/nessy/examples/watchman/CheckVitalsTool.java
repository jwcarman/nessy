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
package org.jwcarman.nessy.examples.watchman;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/** The watchman's lantern: reads the engine room's three gauges. Zero arguments, always ready. */
public final class CheckVitalsTool implements Tool<CheckVitalsTool.Input> {

  public record Input() {}

  private final EngineRoom engineRoom;

  public CheckVitalsTool(EngineRoom engineRoom) {
    this.engineRoom = engineRoom;
  }

  @Override
  public String name() {
    return "check_vitals";
  }

  @Override
  public String description() {
    return "Reads the engine room's current vitals: boiler pressure, bilge level, hull stress."
        + " Use once per round.";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
    EngineRoom.Vitals vitals = engineRoom.read();
    return Awaited.ready(
        ToolResult.ok(
            "boiler pressure "
                + vitals.boilerPressurePsi()
                + " psi; bilge level "
                + vitals.bilgeLevelCm()
                + " cm; hull stress "
                + vitals.hullStressMpa()
                + " MPa"));
  }
}
