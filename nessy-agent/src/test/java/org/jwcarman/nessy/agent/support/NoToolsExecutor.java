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
package org.jwcarman.nessy.agent.support;

import java.time.Duration;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.api.tool.ComputationCallback;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/** Every door, silent: for scopes whose scripts never ask for a tool. */
public final class NoToolsExecutor implements ToolCallExecutor {

  @Override
  public void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink) {
    // no tools in this wiring
  }

  @Override
  public void deferApproval(
      ToolCall call,
      ApprovalRequest request,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink) {
    // no tools in this wiring
  }

  @Override
  public void runTool(ToolCall call, ModelResponseId responseId, Sink sink) {
    // no tools in this wiring
  }

  @Override
  public void deferToolCall(
      ToolCall call,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink) {
    // no tools in this wiring
  }
}
