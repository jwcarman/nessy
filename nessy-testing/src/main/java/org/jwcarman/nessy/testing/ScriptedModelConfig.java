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
package org.jwcarman.nessy.testing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * What {@link ScriptedModel#script(ScriptedModelCustomizer)} hands a customizer: a CONFIG, not a
 * builder (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}. Scripts one
 * turn at a time: each event-adding call ({@link #text}, {@link #thinking}, etc.) appends to the
 * turn under construction, and {@link #endTurn()}/{@link #endWithToolUse()} closes it and starts
 * the next.
 */
public final class ScriptedModelConfig {

  private final List<List<ModelEvent>> turns = new ArrayList<>();
  private List<ModelEvent> current = new ArrayList<>();

  ScriptedModelConfig() {}

  public ScriptedModelConfig text(String text) {
    current.add(new ModelEvent.TextChunk(text));
    return this;
  }

  public ScriptedModelConfig thinking(String text) {
    current.add(new ModelEvent.ThinkingChunk(text));
    return this;
  }

  public ScriptedModelConfig thinkingSigned(String signature) {
    current.add(new ModelEvent.ThinkingSigned(signature));
    return this;
  }

  public ScriptedModelConfig redactedThinking(String data) {
    current.add(new ModelEvent.RedactedThinkingEmitted(data));
    return this;
  }

  public ScriptedModelConfig toolUse(String id, String name, ObjectNode arguments) {
    current.add(new ModelEvent.ToolUseEmitted(new ToolCall(id, name, arguments)));
    return this;
  }

  public ScriptedModelConfig toolUseSigned(
      String id, String name, ObjectNode arguments, String signature) {
    current.add(new ModelEvent.ToolUseEmitted(new ToolCall(id, name, arguments), signature));
    return this;
  }

  public ScriptedModelConfig endTurn() {
    return end(StopReason.END_TURN, Usage.zero());
  }

  public ScriptedModelConfig endTurn(Usage usage) {
    return end(StopReason.END_TURN, usage);
  }

  public ScriptedModelConfig endWithToolUse() {
    return end(StopReason.TOOL_USE, Usage.zero());
  }

  private ScriptedModelConfig end(StopReason reason, Usage usage) {
    current.add(new ModelEvent.TurnEnded(reason, usage));
    turns.add(List.copyOf(current));
    current = new ArrayList<>();
    return this;
  }

  /**
   * Turns this config into the {@link ScriptedModel} it describes — the factory's own step, never a
   * public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * ScriptedModel#script(ScriptedModelCustomizer)}, once {@code customize} has returned.
   *
   * @throws IllegalStateException if the last turn was never ended
   */
  ScriptedModel build() {
    if (!current.isEmpty()) {
      throw new IllegalStateException(
          "last turn was never ended: call endTurn() or endWithToolUse()");
    }
    return new ScriptedModel(turns);
  }
}
