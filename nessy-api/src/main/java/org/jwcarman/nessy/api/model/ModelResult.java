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
package org.jwcarman.nessy.api.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;
import org.jwcarman.nessy.api.message.AssistantMessage;

/**
 * What one model call produced.
 *
 * <p>Sealed because the arms carry genuinely different things. A {@link Replied} has a message and
 * a stop reason. A {@link Refused} has neither — a safety classifier declined, so there may be no
 * content at all — and carries a category and an explanation nothing else does.
 *
 * <p>That split exists to close a real trap: a refusal arrives as HTTP 200, and the provider's own
 * guidance is to check why the turn stopped BEFORE reading its content. A single record with a
 * possibly-empty message gives a consumer no reason to check; two arms make the compiler ask.
 *
 * <p><b>Failures are not here.</b> A rate limit, a timeout, a context overflow — the consumer of
 * those is your code, not the model, so they throw. Only outcomes the conversation itself has to
 * account for appear on this type.
 */
/** Wire names are a compatibility surface. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ModelResult.Replied.class, name = "replied"),
  @JsonSubTypes.Type(value = ModelResult.Refused.class, name = "refused")
})
public sealed interface ModelResult {

  /** What the call cost, whichever way it went. A refusal is billed too. */
  Usage usage();

  /** The model answered. */
  record Replied(AssistantMessage message, StopReason stopReason, Usage usage)
      implements ModelResult {
    public Replied {
      Objects.requireNonNull(message, "message must not be null");
      Objects.requireNonNull(stopReason, "stopReason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /**
   * A safety classifier declined the request.
   *
   * @param category the provider's classification — an open set, so treat it as a label rather than
   *     switching on it exhaustively
   * @param explanation what the provider said, or empty when it said nothing
   */
  record Refused(String category, String explanation, Usage usage) implements ModelResult {
    public Refused {
      Objects.requireNonNull(category, "category must not be null");
      Objects.requireNonNull(explanation, "explanation must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }
}
