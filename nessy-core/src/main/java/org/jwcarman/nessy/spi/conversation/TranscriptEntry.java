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

import java.util.Objects;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;

/**
 * One message as it was born into the transcript, with what its turn cost.
 *
 * @param message the settled message
 * @param turnUsage the usage to attribute to this message: the flushed assistant message of a model
 *     turn carries that turn's usage; every other newborn message, including a compaction's
 *     summary, carries {@link Usage#zero()} — the jurisdiction rule (design §10.6) reserves this
 *     field for the loop's own spend, so a compactor's own call cost is telemetry's, never here
 */
public record TranscriptEntry(Message message, Usage turnUsage) {

  public TranscriptEntry {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(turnUsage, "turnUsage must not be null");
  }
}
