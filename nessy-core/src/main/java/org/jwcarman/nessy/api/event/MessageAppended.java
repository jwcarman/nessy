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
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.Usage;

/**
 * One message, at the moment it was born into a session's transcript.
 *
 * <p>The engine emits this at its newborn choke point — the same point that used to feed a
 * dedicated {@code TranscriptStore} field directly (§10.8) — for every message a reduce produces,
 * in birth order, before anything read-shaped (compaction, elision, windowing) ever gets an
 * opinion. It is the subscription point for everything that wants to follow the transcript:
 * journaling, memory extraction, transcription mirrors, streaming UIs. The journal is no longer a
 * privileged engine dependency; it is simply the first subscriber.
 *
 * @param sessionId the session this message belongs to
 * @param message the settled message, exactly as born
 * @param turnUsage the usage to attribute to this message: the flushed assistant message of a model
 *     turn carries that turn's usage, a compaction's summary carries the strategy's spend, and
 *     every other newborn message carries {@link Usage#zero()}
 */
public record MessageAppended(SessionId sessionId, Message message, Usage turnUsage) {

  public MessageAppended {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(turnUsage, "turnUsage must not be null");
  }
}
