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
package org.jwcarman.nessy.spi;

import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.UsagePolicy;

/**
 * Something that should happen.
 *
 * <p>The reducer emits these; the engine performs them. The reducer never does I/O, so every side
 * effect in the system is named here.
 */
public sealed interface Effect {

  /** Call the model with the conversation as it now stands. */
  record CallModel() implements Effect {
    private static final CallModel INSTANCE = new CallModel();
  }

  /**
   * Resolve the approval question for a call.
   *
   * <p>Note this says <em>resolve</em>, not <em>prompt</em>. The engine consults the call's grant —
   * its {@link UsagePolicy} — before ever asking a human: a policy that resolves to {@code Allow}
   * makes the engine answer {@link Decision#allow()} itself, without troubling the approver, and
   * only {@code RequireApproval} ever reaches it. The reducer stays tool-agnostic and the model
   * still cannot route around the gate.
   */
  record RequestApproval(ToolCall call) implements Effect {}

  /** Run an approved tool. */
  record ExecuteTool(ToolCall call) implements Effect {}

  /**
   * Compact the settled working set. A bare marker, like {@link CallModel}: the reducer no longer
   * decides what to keep versus summarize away — that choice belongs entirely to {@code
   * Reducer#compaction()} now — and the engine hands the compactor the state it already holds, so
   * the effect itself carries no payload.
   */
  record Compact() implements Effect {
    private static final Compact INSTANCE = new Compact();
  }

  /** The one instance of {@link CallModel}; the record has no state, so one is enough. */
  static Effect callModel() {
    return CallModel.INSTANCE;
  }

  /** The one instance of {@link Compact}; the record has no state, so one is enough. */
  static Effect compact() {
    return Compact.INSTANCE;
  }
}
