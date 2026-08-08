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
 * Something that happened.
 *
 * <p>Events are the only input to {@link Reducer}. Streaming text arrives as ordinary events, which
 * is why the loop streams natively instead of growing a second code path for it.
 */
public sealed interface Event {

  /** A human said something. */
  record UserSaid(String text) implements Event {}

  /** A chunk of assistant prose arrived from the stream. */
  record TextDelta(String text) implements Event {}

  /** The model finished emitting one complete tool call. */
  record ToolCallRequested(ToolCall call) implements Event {}

  /** The model's turn is over. */
  record ModelTurnEnded(StopReason reason) implements Event {}

  /** The approval question for one call has been answered. */
  record ApprovalDecided(ToolCall call, Decision decision) implements Event {}

  /** A tool ran to completion, successfully or not. */
  record ToolFinished(ToolCall call, ToolResult result) implements Event {}
}
