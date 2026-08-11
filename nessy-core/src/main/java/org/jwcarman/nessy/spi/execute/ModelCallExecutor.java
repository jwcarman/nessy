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
package org.jwcarman.nessy.spi.execute;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * Performs one {@code CallModel} effect: recalls the context, calls the model, narrates the
 * stream's texture to the observer, and yields exactly one fact — {@code ModelResponded} or {@code
 * ModelCallFailed}. This generation never parks ({@code Awaited.Parked} is reserved for a future
 * batch-call executor); implementations return {@code Ready}.
 */
public interface ModelCallExecutor {

  Awaited<ConversationEvent> execute(ConversationState state, TurnObserver observer);
}
