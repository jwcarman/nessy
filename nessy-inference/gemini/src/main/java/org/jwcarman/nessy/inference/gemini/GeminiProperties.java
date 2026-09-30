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
package org.jwcarman.nessy.inference.gemini;

import java.util.List;
import org.jwcarman.nessy.api.VendorProperty;

/**
 * The {@code gemini.} vendor properties the adapter supports, declared once: set one with {@code
 * property(GeminiProperties.THINKING_LEVEL, GeminiThinkingLevel.HIGH)}, or by name and text, as
 * YAML does. Names are spelled as the Gemini REST reference spells them, {@code generationConfig}
 * included. A name under the prefix that is not one of them is ignored, and said so once when it is
 * checked.
 */
public final class GeminiProperties {

  private static final String THINKING_CONFIG = "gemini.generationConfig.thinkingConfig";

  /** The tokens thinking may spend. */
  public static final VendorProperty<Integer> THINKING_BUDGET =
      VendorProperty.ofInteger(THINKING_CONFIG + ".thinkingBudget");

  /** Whether thought summaries come back. */
  public static final VendorProperty<Boolean> INCLUDE_THOUGHTS =
      VendorProperty.ofBoolean(THINKING_CONFIG + ".includeThoughts");

  /** How much a model thinks. */
  public static final VendorProperty<GeminiThinkingLevel> THINKING_LEVEL =
      VendorProperty.ofEnum(
          THINKING_CONFIG + ".thinkingLevel",
          GeminiThinkingLevel.class,
          GeminiThinkingLevel::spelling);

  /** Every property this adapter supports. */
  public static final List<VendorProperty<?>> SUPPORTED =
      List.of(THINKING_BUDGET, INCLUDE_THOUGHTS, THINKING_LEVEL);

  private GeminiProperties() {}
}
