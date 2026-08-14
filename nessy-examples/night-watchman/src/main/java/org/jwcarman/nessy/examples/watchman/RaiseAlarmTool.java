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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The alarm bell: logs at WARN, loudly and obviously fake — no pager is harmed (spec §2, the
 * coupon-tool ethos).
 */
public final class RaiseAlarmTool implements Tool<RaiseAlarmTool.Input> {

  private static final Logger LOGGER = LoggerFactory.getLogger(RaiseAlarmTool.class);

  public record Input(String severity, String reason) {}

  @Override
  public String name() {
    return "raise_alarm";
  }

  @Override
  public String description() {
    return "Raises the engine-room alarm. Use decisively when a vital is out of its normal band"
        + " or clearly trending toward it.";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
    LOGGER.warn("ALARM [{}] {}", input.severity(), input.reason());
    return Awaited.ready(
        ToolResult.ok(
            "Alarm raised (demo — nothing real was paged): ["
                + input.severity()
                + "] "
                + input.reason()));
  }
}
