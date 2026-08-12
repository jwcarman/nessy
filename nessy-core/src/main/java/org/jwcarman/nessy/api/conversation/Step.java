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
package org.jwcarman.nessy.api.conversation;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Message;

/**
 * What one fold produced: the next state, what to remember, and what to do about it.
 *
 * <p>{@code remember} is the fold's message births — a tell's own fold births nothing (it only
 * joins the {@link ConversationState#told()} accumulator); the user message is born when the drain
 * ({@link ConversationState#openTurn()}) merges those queued notes into an open turn, the assistant
 * message a response carried, the results message a cleared debt flushed — in birth order, for the
 * loop to tell Memory before performing any effect.
 */
public record Step(ConversationState state, List<Message> remember, List<Effect> effects) {

  public Step {
    Objects.requireNonNull(state, "state must not be null");
    remember = List.copyOf(remember);
    effects = List.copyOf(effects);
  }

  public static Step of(ConversationState state, Effect... effects) {
    return new Step(state, List.of(), List.of(effects));
  }

  public static Step of(ConversationState state, List<Message> remember, Effect... effects) {
    return new Step(state, remember, List.of(effects));
  }
}
