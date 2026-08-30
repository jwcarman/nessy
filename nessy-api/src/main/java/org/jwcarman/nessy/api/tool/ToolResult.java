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
package org.jwcarman.nessy.api.tool;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolResultContentBlock;

/**
 * What a tool call produced: it worked and gave you something, or it did not and told you why.
 *
 * <p>The two arms carry different things, which is why this is sealed rather than a flag. A success
 * carries CONTENT — structured, and one day more than text. A failure carries an EXPLANATION, which
 * is always prose, because the party that reads it is the model, deciding whether to try again.
 *
 * <p><b>It says nothing about which call it answers.</b> {@code toolUseId} lives on {@code
 * ToolResultBlock} and only the engine sets it, so a tool cannot echo the wrong id, answer a call
 * it was not asked about, or answer two. That is the one thing a tool is not allowed to author.
 *
 * <p><b>Both a tool and the engine produce these.</b> A tool reports its own failure; the engine
 * reports an unknown tool name, arguments that will not bind, a thrown exception, a denied
 * approval, an expired deferral. Whether the tool's code actually RAN is deliberately not modelled
 * as a third arm — nothing branches on it, and the only party that needs to know is the model,
 * which reads prose. So the MESSAGE says it: "the call was not made" versus "it may have partially
 * completed."
 */
/** Wire names are a compatibility surface: a parked call's claimed result names them. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ToolResult.Success.class, name = "success"),
  @JsonSubTypes.Type(value = ToolResult.Failure.class, name = "failure")
})
public sealed interface ToolResult {

  /** The call produced an answer. */
  record Success(List<ToolResultContentBlock> content) implements ToolResult {
    public Success {
      Objects.requireNonNull(content, "content must not be null");
      content = List.copyOf(content);
    }
  }

  /** The call produced no answer, and this is why — in words the model can act on. */
  record Failure(String message) implements ToolResult {
    public Failure {
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /** Succeeded, with a text answer. */
  static ToolResult ok(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return new Success(List.of(new TextBlock(text)));
  }

  /** Succeeded, with content. */
  static ToolResult ok(List<ToolResultContentBlock> content) {
    return new Success(content);
  }

  /** Did not succeed. State whether the tool's code ran — the model uses that to judge a retry. */
  static ToolResult error(String message) {
    return new Failure(message);
  }
}
