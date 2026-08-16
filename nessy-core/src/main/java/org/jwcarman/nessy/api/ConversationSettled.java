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
package org.jwcarman.nessy.api;

import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.event.ConversationScoped;

/**
 * A drive settled — {@link ConversationStatus#COMPLETE} or {@link ConversationStatus#FAILED} — and
 * has nothing left to do until the world tells it something new. Published, never folded: unlike
 * {@link ConversationEvent}, this is not an input to {@link
 * org.jwcarman.nessy.api.conversation.ConversationState#fold ConversationState.fold} — it is the
 * loop announcing a settlement it already reached, the wake-up signal a listener (a subagent
 * dispatcher, an outbound webhook) waits for.
 *
 * <p>Never published for {@link ConversationStatus#PARKED}: a park is a pause, waiting on the
 * world, not a settlement — there is nothing here yet for a listener to act on.
 *
 * <p>At-least-once, like every fact this loop emits (design §2): a re-driven replay that finds the
 * conversation already settled may publish this fact again for the very same settlement. Listeners
 * must be idempotent — keyed by {@code conversationId} and {@code status}, or otherwise tolerant of
 * a repeat.
 *
 * @param conversationId the conversation that settled
 * @param status {@code COMPLETE} or {@code FAILED} — never {@code PARKED}
 * @param failureReason why the session failed, or {@code null} when {@code status} is {@code
 *     COMPLETE}
 * @param finalAssistantText the concatenated text blocks of the last {@code ASSISTANT} message in
 *     the settled conversation, in order; the empty string when there is none — never {@code null}
 */
public record ConversationSettled(
    ConversationId conversationId,
    ConversationStatus status,
    String failureReason,
    String finalAssistantText)
    implements ConversationScoped {

  public ConversationSettled {
    Objects.requireNonNull(conversationId, "conversationId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(finalAssistantText, "finalAssistantText must not be null");
  }
}
