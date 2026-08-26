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
package org.jwcarman.nessy.spi.model;

/**
 * Something a provider may or may not be able to do.
 *
 * <p>This enum is the anti-rot mechanism for the model seam. A request may <em>ask</em> for prompt
 * caching; a provider that cannot do it says so, and the harness degrades explicitly. Flattening
 * every model to what the weakest one supports is how the 2023-era abstractions died.
 */
public enum Capability {
  THINKING,
  PROMPT_CACHING,

  /**
   * Prompt caching with an entry that lives an HOUR rather than the provider's default few minutes.
   *
   * <p>A separate word because it is a separate decision with its own bill, not a knob on {@link
   * #PROMPT_CACHING}. Anthropic's default ephemeral entry lives five minutes; an agent whose rounds
   * are further apart than that can never read one back, however well its breakpoints are placed.
   * The long entry costs "2 times the base input tokens price" on WRITES (against 1.25x for the
   * default), while reads stay at 0.1x — so it pays exactly when rounds land more than five minutes
   * and less than an hour apart, and is a straight loss when they land closer together.
   *
   * <p>Asking for this is asking for caching: a provider that honours it turns caching on, so
   * {@link #PROMPT_CACHING} need not also be listed. A provider that cannot do the long entry says
   * so through {@code capabilities()}, exactly as it does for anything else here.
   */
  PROMPT_CACHING_1H,
  PARALLEL_TOOL_CALLS,
  IMAGE_INPUT
}
