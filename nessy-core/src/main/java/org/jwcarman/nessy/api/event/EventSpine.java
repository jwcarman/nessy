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
package org.jwcarman.nessy.api.event;

import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * One agent's whole delivery apparatus: emission (inherited from {@link EventEmitter}) plus the one
 * per-conversation dynamic view (design §17).
 *
 * <p>Built once, from a frozen chain of {@link ListenerDeclaration}s, via {@link EventSpines#of}.
 * Nothing about the frozen chain is ever mutated after construction — that is what "frozen at
 * build" means; the only thing that ever changes at runtime is the live set of per-conversation
 * subscriptions each {@link #forConversation} view manages.
 *
 * <p>Deliberately narrower than the retired {@code EventHub}: there is no general, agent-wide
 * {@code subscribe} here. An agent-wide observer is declared once, at build time, via {@code
 * listen}/{@code listenAsync} on the builder; the only thing left to attach at runtime is a single
 * conversation's own traffic, through {@link #forConversation}.
 */
public interface EventSpine extends EventEmitter {

  /**
   * A view over this spine already scoped to {@code conversationId}: {@link
   * ConversationEvents#subscribe} on the result only ever delivers events {@link
   * ConversationScoped} to that one id.
   */
  ConversationEvents forConversation(ConversationId conversationId);
}
