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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.api.tool.ToolCall;

/** Answers each call by id with its scripted outcome, asynchronously through the pump. */
public final class ScriptedToolExecutor implements ToolCallExecutor {

  private final Executor pump;
  private final Map<String, ToolOutcome> outcomes = new HashMap<>();
  private final List<ToolCall> executed = new ArrayList<>();

  public ScriptedToolExecutor(Executor pump) {
    this.pump = pump;
  }

  public void answer(String callId, ToolOutcome outcome) {
    outcomes.put(callId, outcome);
  }

  @Override
  public void executeTool(ToolCall call, ModelResponseId responseId, Sink sink) {
    executed.add(call);
    ToolOutcome outcome = outcomes.get(call.id());
    if (outcome == null) {
      throw new IllegalStateException("no scripted outcome for call " + call.id());
    }
    pump.execute(() -> sink.deliver(new AgentEvent.ToolFinished(call, outcome)));
  }

  public List<ToolCall> executed() {
    return List.copyOf(executed);
  }
}
