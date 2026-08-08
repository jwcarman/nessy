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
package org.jwcarman.nessy.core;

/**
 * Something that should happen.
 *
 * <p>The reducer emits these; the engine performs them. The reducer never does I/O, so every side
 * effect in the system is named here.
 */
public sealed interface Effect {

  /** Call the model with the conversation as it now stands. */
  record CallModel() implements Effect {}

  /**
   * Resolve the approval question for a call.
   *
   * <p>Note this says <em>resolve</em>, not <em>prompt</em>. For a tool whose {@code
   * requiresApproval()} is false the engine answers {@link Decision#allow()} itself without
   * troubling the approver. The reducer stays tool-agnostic and the model still cannot route around
   * the gate.
   */
  record RequestApproval(ToolCall call) implements Effect {}

  /** Run an approved tool. */
  record ExecuteTool(ToolCall call) implements Effect {}

  CallModel CALL_MODEL = new CallModel();

  static Effect callModel() {
    return CALL_MODEL;
  }
}
