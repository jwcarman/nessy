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
package org.jwcarman.nessy.examples.approvals;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The one tool this demo carries: DURABLE completion, so the harness's own filter admits it, and
 * gated behind {@code requireApproval()} in {@link Approvals} so it never runs without a human's
 * yes.
 */
final class RestartTool implements Tool<RestartInput> {

  @Override
  public String name() {
    return "restart";
  }

  @Override
  public String description() {
    return "restarts a production target; requires human approval";
  }

  @Override
  public Class<RestartInput> inputType() {
    return RestartInput.class;
  }

  @Override
  public CompletionPolicy requiredCompletion() {
    return CompletionPolicy.DURABLE;
  }

  @Override
  public Awaited<ToolResult> execute(RestartInput input, ToolContext context) {
    return Awaited.ready(ToolResult.ok("restarted " + input.target()));
  }
}
