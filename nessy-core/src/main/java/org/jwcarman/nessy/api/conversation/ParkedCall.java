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

import java.util.Objects;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Homework waiting on the world, as an approval card: the tool call that yielded, named by the
 * token it parked under.
 *
 * <p>This is no longer the state's own vocabulary (design §5) — the {@link
 * org.jwcarman.nessy.api.conversation.ConversationState} tracks only that a call is outstanding.
 * {@code ParkedCall} survives as the read-side shape a {@link
 * org.jwcarman.nessy.spi.conversation.Parks} registry entry renders into: {@link
 * org.jwcarman.nessy.Agent#snapshot} and {@link org.jwcarman.nessy.Harness#peek} both hand this
 * record back, pairing a wait's token with its call for a caller building an approval UI.
 */
public record ParkedCall(ParkToken token, ToolCall call) {

  public ParkedCall {
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(call, "call must not be null");
  }
}
