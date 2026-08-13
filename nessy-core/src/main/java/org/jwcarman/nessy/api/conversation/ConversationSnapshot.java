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
import org.jwcarman.nessy.api.message.Context;

/**
 * The total page-rebuild read: everything a fresh page load needs to redraw one conversation,
 * whether or not it has ever been stored.
 *
 * @param status the lifecycle position; {@link ConversationStatus#IDLE} for a conversation never
 *     stored
 * @param parkedCalls homework waiting on the world, empty for a conversation never stored
 * @param context the same assembly a live call would see, {@link Context#empty()} for a
 *     conversation never stored
 */
public record ConversationSnapshot(
    ConversationStatus status, List<ParkedCall> parkedCalls, Context context) {

  public ConversationSnapshot {
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(parkedCalls, "parkedCalls must not be null");
    Objects.requireNonNull(context, "context must not be null");
    parkedCalls = List.copyOf(parkedCalls);
  }
}
