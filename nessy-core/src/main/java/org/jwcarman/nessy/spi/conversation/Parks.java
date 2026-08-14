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
package org.jwcarman.nessy.spi.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * The callback door's own registry: where a parked wait lives between the moment a tool call hands
 * its token to the outside world and the moment a resume presents that token back.
 *
 * <p>The conversation itself no longer knows about tokens (design §5) — it knows only that a call
 * is still outstanding, matched by call id. {@code Parks} is where the token side of the story
 * lives instead: a durable, keep-forever record of every wait this process has ever registered, so
 * a callback arriving days later can still translate its token into the conversation and call it
 * names.
 *
 * <p>Registry entries are never deleted once resolved (design §5): they are the durable record that
 * a token once named a particular wait, the same keep-forever posture the retired {@code
 * nessy_token} table already had. Replay protection — refusing to re-execute a call a redelivered
 * resolution names twice — is the fold's own is-this-call-still-outstanding question, not this
 * registry's to answer.
 */
public interface Parks {

  /** A parked wait, as the registry knows it: whose conversation, which call, which token. */
  record Park(ConversationId conversationId, ParkToken token, ToolCall call) {

    public Park {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
      Objects.requireNonNull(token, "token must not be null");
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /** Registers a wait. Idempotent on token (at-least-once loop retries re-register). */
  void park(Park park);

  /** The callback door's translation: token → the wait it names. */
  Optional<Park> find(ParkToken token);

  /** Every wait ever registered for {@code id} — the approval-card read, filtered by the caller. */
  List<Park> forConversation(ConversationId id);

  /** The zero-configuration default: waits live in this JVM and die with it. */
  static Parks inMemory() {
    return new InMemoryParks();
  }
}
