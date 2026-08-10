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

import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;

/**
 * One message, at the moment it was born into a conversation's transcript.
 *
 * <p>The engine emits this at its newborn choke point for every message a reduce produces, in birth
 * order, before anything read-shaped (compaction, elision, windowing) ever gets an opinion. It is
 * the declaration point for everything that wants to follow the transcript: journaling, memory
 * extraction, transcription mirrors, streaming UIs. The journal is not a privileged engine
 * dependency — it is simply a declared listener for this event (design §17).
 *
 * @param conversationId the conversation this message belongs to
 * @param message the settled message, exactly as born
 * @param turnUsage the usage to attribute to this message: the flushed assistant message of a model
 *     turn carries that turn's usage; every other newborn message, including a compaction's
 *     summary, carries {@link Usage#zero()} — the jurisdiction rule (design §10.6) reserves this
 *     field for the loop's own spend, so a compactor's own call cost is telemetry's, never here
 */
public record MessageAppended(ConversationId conversationId, Message message, Usage turnUsage)
    implements ConversationScoped {

  public MessageAppended {
    Objects.requireNonNull(conversationId, "conversationId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(turnUsage, "turnUsage must not be null");
  }
}
