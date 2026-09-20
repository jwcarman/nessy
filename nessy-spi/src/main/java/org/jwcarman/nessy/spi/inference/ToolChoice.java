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
package org.jwcarman.nessy.spi.inference;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * Whether the model may reach for a tool this turn, and which.
 *
 * <p>Offering tools and requiring one are different questions, and until now only the first could
 * be asked: a request carried the tools and the model decided. That is right nearly always, and
 * wrong in the two places worth naming. A turn that has gone round the loop enough times needs to
 * be told to answer rather than call again. And a caller that wants a particular shape back can
 * offer one tool, require it, and read the arguments -- the tool never runs, because what was
 * wanted was the shape of its input, not the work behind it.
 *
 * <p>Every vendor spells this, so it is translation rather than emulation: {@code ToolChoiceTool}
 * on Anthropic, {@code ChatCompletionNamedToolChoice} on OpenAI, {@code FunctionCallingConfig} on
 * Gemini, {@code ToolChoice} on Bedrock.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ToolChoice.Auto.class, name = "auto"),
  @JsonSubTypes.Type(value = ToolChoice.None.class, name = "none"),
  @JsonSubTypes.Type(value = ToolChoice.Any.class, name = "any"),
  @JsonSubTypes.Type(value = ToolChoice.Named.class, name = "named")
})
public sealed interface ToolChoice {

  /**
   * The model decides, which is what a turn wants unless something says otherwise.
   *
   * <p>The default, and the behaviour of every request written before this existed.
   */
  record Auto() implements ToolChoice {}

  /**
   * No tool, whatever is offered.
   *
   * <p><b>Rarely what is wanted.</b> An application that does not want a tool called should not
   * offer the tool: that works on every vendor, sends no schemas, and cannot be dropped in
   * translation. This exists for the one case where the tools have to stay in the request -- they
   * are the cached prefix on vendors that cache, so taking them out for a turn throws the cache
   * away.
   *
   * <p>Two things it does not promise. It is not a way to make a model answer: measured against
   * Anthropic on 2026-09-20, a ban with tools still in the request ends the turn with no content at
   * all. And Bedrock's Converse cannot say it, so that adapter refuses rather than sending
   * something weaker.
   */
  record None() implements ToolChoice {}

  /** Some tool, the model's pick of those offered. An answer without a call is not an option. */
  record Any() implements ToolChoice {}

  /**
   * This tool, named. The one to use when the point is the shape of the arguments rather than a
   * choice between tools.
   */
  record Named(ToolName name) implements ToolChoice {
    public Named {
      Objects.requireNonNull(name, "name must not be null");
    }
  }

  /** What a request means when it says nothing. */
  static ToolChoice auto() {
    return new Auto();
  }
}
