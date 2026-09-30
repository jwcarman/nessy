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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jwcarman.nessy.api.VendorProperties;
import org.jwcarman.nessy.api.VendorProperty;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code anthropic.} properties {@link AnthropicProperties} declares, from the merged
 * provider and agent-type map. Logs under {@link AnthropicProperties}, the class a reader of the
 * log looks for.
 */
final class AnthropicPropertyReader {

  static final String PREFIX = "anthropic.";

  /** The properties this adapter supports, by full name. */
  static final Map<String, VendorProperty<?>> SUPPORTED =
      Map.of(
          AnthropicProperties.THINKING_BUDGET.name(), AnthropicProperties.THINKING_BUDGET,
          AnthropicProperties.THINKING_TYPE.name(), AnthropicProperties.THINKING_TYPE,
          AnthropicProperties.CACHE_TTL.name(), AnthropicProperties.CACHE_TTL,
          AnthropicProperties.SERVICE_TIER.name(), AnthropicProperties.SERVICE_TIER);

  // Anthropic's floor for the thinking budget: the lowest value the API accepts, so the only one
  // guaranteed to leave room under a small maxTokens.
  private static final int DEFAULT_THINKING_BUDGET = 1024;

  private static final Logger log = LoggerFactory.getLogger(AnthropicProperties.class);

  private AnthropicPropertyReader() {}

  /**
   * The {@code anthropic.} properties once read.
   *
   * @param thinking empty when not thinking (absent, or {@code disabled}); otherwise {@code
   *     enabled} or {@code adaptive}
   * @param budget the thinking budget; always present when {@code thinking} is {@code enabled}
   */
  record Read(
      Optional<AnthropicThinkingType> thinking,
      OptionalInt budget,
      Optional<AnthropicCacheTtl> cacheTtl,
      Optional<AnthropicServiceTier> serviceTier) {

    boolean enabled() {
      return thinking.filter(AnthropicThinkingType.ENABLED::equals).isPresent();
    }
  }

  /** The supported names of the merged provider and agent-type map, parsed. Silent. */
  static Read read(Map<String, String> merged) {
    VendorProperties.under(merged, PREFIX);

    Optional<Integer> budget = AnthropicProperties.THINKING_BUDGET.in(merged);
    Optional<AnthropicThinkingType> type = AnthropicProperties.THINKING_TYPE.in(merged);
    // Absent with a budget present means enabled (§9c).
    Optional<AnthropicThinkingType> thinking = type;
    if (type.isEmpty() && budget.isPresent()) {
      thinking = Optional.of(AnthropicThinkingType.ENABLED);
    }
    if (thinking.filter(AnthropicThinkingType.ENABLED::equals).isPresent() && budget.isEmpty()) {
      budget = Optional.of(DEFAULT_THINKING_BUDGET);
    }
    return new Read(
        thinking.filter(value -> value != AnthropicThinkingType.DISABLED),
        budget.map(OptionalInt::of).orElseGet(OptionalInt::empty),
        AnthropicProperties.CACHE_TTL.in(merged),
        AnthropicProperties.SERVICE_TIER.in(merged));
  }

  /** The budget is spent out of maxTokens, so a ceiling at or below it leaves nothing to answer. */
  static void requireHeadroom(Read read, InferenceOptions options) {
    if (read.enabled() && options.maxTokens() <= read.budget().getAsInt()) {
      throw new IllegalArgumentException(
          "maxTokens (%d) must be greater than the thinking budget (%d)"
              .formatted(options.maxTokens(), read.budget().getAsInt()));
    }
  }

  /** A provider is one adapter: a provider-level entry under another prefix is a mistake (§6a). */
  static void requireOwn(Map<String, String> properties) {
    for (String name : properties.keySet()) {
      if (!name.startsWith(PREFIX)) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' is not under '"
                + PREFIX
                + "'; a provider reads only its own prefix");
      }
    }
  }

  /**
   * Says, once per name, that a property under this prefix is not one this adapter supports and is
   * ignored. Called when a property set is first checked (a provider's build, a harness's
   * validate), never per request.
   */
  static void warnUnsupported(Map<String, String> merged) {
    List<String> supported = SUPPORTED.keySet().stream().sorted().toList();
    for (String name : VendorProperties.under(merged, PREFIX).keySet()) {
      if (!supported.contains(PREFIX + name)) {
        log.warn(
            "NESSY INFERENCE: property '{}{}' is not supported by anthropic and is ignored;"
                + " supported: {}",
            PREFIX,
            name,
            supported);
      }
    }
  }

  static void logIgnored(Map<String, String> merged) {
    if (log.isDebugEnabled()) {
      List<String> others = merged.keySet().stream().filter(n -> !n.startsWith(PREFIX)).toList();
      if (!others.isEmpty()) {
        log.debug("NESSY INFERENCE: properties for other adapters, ignored here: {}", others);
      }
    }
  }
}
