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
 *
 * <p>With nothing set no thinking field is sent, and what the model then does is the model's own
 * default: Sonnet 5.5 thinks (measured 2026-10-02).
 */
public enum AnthropicThinkingType {
  ENABLED,

  /** Sends no thinking field, exactly as when nothing is set. */
  DISABLED,
  ADAPTIVE,

  /**
   * Sent as {@code between_tools}: the model does not think before it responds, and the short
   * updates it writes between tool calls come back as thinking blocks. It is how thinking is turned
   * off on a model that thinks unasked and refuses {@code disabled}, as Sonnet 5.5 does (measured
   * 2026-10-02).
   */
  BETWEEN_TOOLS;
}
