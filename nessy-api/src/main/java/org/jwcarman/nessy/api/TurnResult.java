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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * How a turn ended.
 *
 * <p>Not the same thing as how one model CALL ended: a turn may span several calls — the model asks
 * for tools, the tools run, the model is asked again — so {@code TOOL_USE} is never a turn ending,
 * it is the middle. These four are the ways a turn actually stops.
 *
 * <p>Sealed so a watcher rendering a turn cannot forget the awkward ones. A chat UI shows a
 * completed turn, a truncated one, and a refused one differently, and a failed one differently
 * again; a single reason string would let all four render identically by accident.
 *
 * <p>Wire names are a compatibility surface: narration delivered over SSE names them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TurnResult.Completed.class, name = "completed"),
  @JsonSubTypes.Type(value = TurnResult.Truncated.class, name = "truncated"),
  @JsonSubTypes.Type(value = TurnResult.Refused.class, name = "refused"),
  @JsonSubTypes.Type(value = TurnResult.Failed.class, name = "failed")
})
public sealed interface TurnResult {

  /** The model finished what it had to say. */
  record Completed() implements TurnResult {}

  /** The output ceiling was reached — the answer is cut off mid-thought, not finished. */
  record Truncated() implements TurnResult {}

  /**
   * A safety classifier declined.
   *
   * @param category the provider's classification — an open set; a label, not something to switch
   *     on exhaustively
   * @param explanation what the provider said, or empty
   */
  record Refused(String category, String explanation) implements TurnResult {
    public Refused {
      Objects.requireNonNull(category, "category must not be null");
      Objects.requireNonNull(explanation, "explanation must not be null");
    }
  }

  /** The turn stopped because something broke — a rate limit, a timeout, a context overflow. */
  record Failed(String reason) implements TurnResult {
    public Failed {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
