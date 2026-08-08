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
package org.jwcarman.nessy.model;

import org.jwcarman.nessy.core.StopReason;
import org.jwcarman.nessy.core.ToolCall;

/**
 * Something a provider emitted while streaming one turn.
 *
 * <p>Distinct from {@code Event} on purpose: a provider should be able to report what the model
 * did, and nothing else. Reusing the core event type would let a provider inject a user message or
 * an approval decision into the loop.
 */
public sealed interface ModelEvent {

  record TextChunk(String text) implements ModelEvent {}

  /** Emitted once the provider has assembled a complete tool call. */
  record ToolUseEmitted(ToolCall call) implements ModelEvent {}

  record TurnEnded(StopReason reason) implements ModelEvent {}
}
