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
package org.jwcarman.nessy.examples.dispatcher;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The dispatcher's one tool: the crew is out in the world, so this always parks (spec §2, §3) —
 * unlike {@code chat-web}'s coupon tool or {@code night-watchman}'s pair, {@code execute} never
 * returns {@code Ready}.
 *
 * <p>Unlike order-desk's machine-half verbs, there is no outbound transport here — no queue
 * publish, no webhook fired to notify a dispatch system. The park token reaches the world purely
 * through narration: {@link ToolContext#progress} on this call, which the app's turn observer logs
 * (spec §3's "the app logs the park token"), and {@code GET /incidents/{id}}'s snapshot, which
 * lists every open park's token alongside its tool (spec §3's last bullet). The operator's curl is
 * the whole delivery mechanism.
 */
public final class RequestFieldCrewTool implements Tool<RequestFieldCrewTool.Input> {

  public record Input(String incidentId, String action) {}

  @Override
  public String name() {
    return "request_field_crew";
  }

  @Override
  public String description() {
    return "Requests a field crew be dispatched for an incident. Call exactly once per incident"
        + " unless told otherwise; the crew's outcome arrives later via a callback.";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
    ParkToken token = ParkToken.generate();
    context.progress("crew requested; awaiting confirmation callback");
    return Awaited.parked(token);
  }
}
