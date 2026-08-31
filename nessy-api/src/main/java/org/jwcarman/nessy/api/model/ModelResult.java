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
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AssistantMessage;

/**
 * What one model call produced.
 *
 * <p>Sealed because the arms carry genuinely different things. {@link Answered} has a message and a
 * stop reason. {@link Asked} has neither a message nor an ending — the model wants tools run, the
 * results do not exist yet, and the turn is only half over. {@link Refused} has no content at all:
 * a safety classifier declined, and it carries a category and explanation nothing else does.
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
  @JsonSubTypes.Type(value = ModelResult.Answered.class, name = "answered"),
  @JsonSubTypes.Type(value = ModelResult.Asked.class, name = "asked"),
  @JsonSubTypes.Type(value = ModelResult.Refused.class, name = "refused")
})
public sealed interface ModelResult {

  /** What the call cost, whichever way it went. A refusal is billed too. */
  Usage usage();

  /**
   * The model answered.
   *
   * <p>The stop reason still matters: an answer that ran out of room is not the same as one that
   * finished.
   */
  record Answered(AssistantMessage message, StopReason stopReason, Usage usage)
      implements ModelResult {
    public Answered {
      Objects.requireNonNull(message, "message must not be null");
      Objects.requireNonNull(stopReason, "stopReason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /**
   * The model wants tools run before it will continue.
   *
   * <p>Content without answers: the calls it made, whatever it said while making them, and any
   * provider state that must travel with them. Pairing results into an {@link
   * org.jwcarman.nessy.api.message.ExchangeMessage} is the engine's job, and until that is done
   * there is no message to be had — which is why this arm carries content instead of one.
   *
   * <p>No {@code StopReason}: this IS the stop reason. Carrying one as well would permit a result
   * claiming {@code END_TURN} while holding tool calls, a state nothing could act on.
   */
  record Asked(List<ExchangeContentBlock> content, Usage usage) implements ModelResult {
    public Asked {
      Objects.requireNonNull(content, "content must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
      content = List.copyOf(content);
      if (content.stream().noneMatch(ToolCallBlock.class::isInstance)) {
        throw new IllegalArgumentException("asked for nothing: no tool calls");
      }
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
