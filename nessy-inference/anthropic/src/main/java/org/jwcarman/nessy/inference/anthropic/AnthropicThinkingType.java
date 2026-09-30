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
package org.jwcarman.nessy.inference.anthropic;

/**
 * Whether and how a model thinks, as the value of {@code anthropic.thinking.type}. {@link #ENABLED}
 * needs {@code anthropic.thinking.budget_tokens}.
 */
public enum AnthropicThinkingType {
  ENABLED("enabled"),
  DISABLED("disabled"),
  ADAPTIVE("adaptive");

  private final String spelling;

  AnthropicThinkingType(String spelling) {
    this.spelling = spelling;
  }

  /** The text the vendor spells this value as, and the text a property carries. */
  public String spelling() {
    return spelling;
  }
}
