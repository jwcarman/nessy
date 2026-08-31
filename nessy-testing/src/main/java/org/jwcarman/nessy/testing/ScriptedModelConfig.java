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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * What {@link ScriptedModel#script(ScriptedModelCustomizer)} hands a customizer: a CONFIG, not a
 * builder (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}. Scripts one
 * turn at a time: each event-adding call ({@link #text}, {@link #thinking}, etc.) appends to the
 * turn under construction, and {@link #endTurn()}/{@link #endWithToolCalls()} closes it and starts
 * the next.
 */
public final class ScriptedModelConfig {

  /**
   * What a turn scripted without a usage reports: nothing, rather than a call that cost nothing. A
   * script that did not mention tokens has not claimed the turn was free, and saying so keeps every
   * engine test that ends a turn this way exercising the unreported path.
   */
  private static final Usage NOTHING = Usage.unreported();

  private final List<List<ModelEvent>> turns = new ArrayList<>();
  private List<ModelEvent> current = new ArrayList<>();

  ScriptedModelConfig() {}

  public ScriptedModelConfig text(String text) {
    current.add(new ModelEvent.TextChunk(text));
    return this;
  }

  /** Reasoning shown as it is produced. Narrated to whoever is watching, and never stored. */
  public ScriptedModelConfig reasoning(String text) {
    current.add(new ModelEvent.ReasoningChunk(text));
    return this;
  }

  /**
   * State a provider wants handed back.
   *
   * <p>The payload is whatever that provider's adapter would build; a script asserting on replay
   * supplies the same shape it expects to read.
   */
  public ScriptedModelConfig providerState(String provider, JsonNode data) {
    current.add(new ModelEvent.ProviderStateEmitted(provider, data));
    return this;
  }

  public ScriptedModelConfig toolCall(String id, String name, ObjectNode arguments) {
    current.add(new ModelEvent.ToolCallEmitted(new ToolCall(id, name, arguments)));
    return this;
  }

  public ScriptedModelConfig endTurn() {
    return end(StopReason.END_TURN, NOTHING);
  }

  public ScriptedModelConfig endTurn(Usage usage) {
    return end(StopReason.END_TURN, usage);
  }

  public ScriptedModelConfig endWithToolCalls() {
    return end(StopReason.TOOL_USE, NOTHING);
  }

  /** The provider declined the whole turn — nothing was said, and nothing will be. */
  public ScriptedModelConfig refuse(String category, String explanation) {
    current.add(new ModelEvent.Refused(category, explanation, NOTHING));
    turns.add(List.copyOf(current));
    current = new ArrayList<>();
    return this;
  }

  private ScriptedModelConfig end(StopReason reason, Usage usage) {
    current.add(new ModelEvent.Stopped(reason, usage));
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
          "last turn was never ended: call endTurn(), endWithToolCalls() or refuse()");
    }
    return new ScriptedModel(turns);
  }
}
