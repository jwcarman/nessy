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

import org.jwcarman.nessy.api.VendorProperty;

/**
 * The {@code anthropic.} vendor properties the adapter supports, declared once: set one with {@code
 * property(AnthropicProperties.CACHE_TTL, AnthropicCacheTtl.ONE_HOUR)}, or by name and text, as
 * YAML does. A name under the prefix that is not one of them is ignored, and said so once when it
 * is checked.
 */
public final class AnthropicProperties {

  /** Whether and how a model thinks. A budget alone means enabled. */
  public static final VendorProperty<AnthropicThinkingType> THINKING_TYPE =
      VendorProperty.ofEnum("anthropic.thinking.type", AnthropicThinkingType.class);

  /** The tokens thinking may spend, out of the request's maxTokens. */
  public static final VendorProperty<Integer> THINKING_BUDGET =
      VendorProperty.ofInteger("anthropic.thinking.budget_tokens");

  /** How long the prompt-cache marker lasts. */
  public static final VendorProperty<AnthropicCacheTtl> CACHE_TTL =
      VendorProperty.ofEnum("anthropic.cache_control.ttl", AnthropicCacheTtl.class);

  /** Which capacity a request may use. */
  public static final VendorProperty<AnthropicServiceTier> SERVICE_TIER =
      VendorProperty.ofEnum("anthropic.service_tier", AnthropicServiceTier.class);

  private AnthropicProperties() {}
}
