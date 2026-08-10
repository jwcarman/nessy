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
 * Something self-attributing enough to name the one conversation it belongs to.
 *
 * <p>Implemented by the sealed {@code ConversationEvent} grammar and by every open notice ({@link
 * MessageAppended}, {@link ToolProgress}, {@link CompactionFailed}, {@link EnrichmentFailed}) —
 * this is what a conversation-scoped {@link ConversationEvents} subscription filters on. An emitted
 * object that does not implement this interface is simply invisible to conversation-local delivery;
 * it still reaches whatever declared (harness- or agent-level) listeners are frozen for its type.
 */
public interface ConversationScoped {

  ConversationId conversationId();
}
