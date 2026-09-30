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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code anthropic.} vendor properties (spec §9c): thinking, the cache marker's lifetime and
 * the service tier, and nothing else. A name under the prefix that is not one of them is ignored,
 * and said so once when it is checked.
 */
final class AnthropicProperties {

  static final String PREFIX = "anthropic.";
  static final String THINKING_TYPE = "thinking.type";
  static final String THINKING_BUDGET = "thinking.budget_tokens";
  static final String CACHE_TTL = "cache_control.ttl";
  static final String SERVICE_TIER = "service_tier";

  static final String ENABLED = "enabled";
  static final String ADAPTIVE = "adaptive";
  private static final String DISABLED = "disabled";

  private static final Set<String> THINKING_TYPES = Set.of(ENABLED, ADAPTIVE, DISABLED);

  private static final Set<String> KNOWN =
      Set.of(THINKING_TYPE, THINKING_BUDGET, CACHE_TTL, SERVICE_TIER);

  private static final Logger log = LoggerFactory.getLogger(AnthropicProperties.class);

  private AnthropicProperties() {}

  /**
   * The {@code anthropic.} properties once read.
   *
   * @param thinking empty when not thinking (absent, or {@code disabled}); otherwise {@code
   *     enabled} or {@code adaptive}
   * @param budget the thinking budget; always present when {@code thinking} is {@code enabled}
   */
  record Read(
      Optional<String> thinking,
      OptionalInt budget,
      Optional<String> cacheTtl,
      Optional<String> serviceTier) {

    boolean enabled() {
      return thinking.filter(ENABLED::equals).isPresent();
    }
  }

  /** The supported names of the merged provider and agent-type map, parsed. Silent. */
  static Read read(Map<String, String> merged) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);

    OptionalInt budget =
        own.containsKey(THINKING_BUDGET)
            ? OptionalInt.of(
                VendorProperties.requireInteger(PREFIX + THINKING_BUDGET, own.get(THINKING_BUDGET)))
            : OptionalInt.empty();
    Optional<String> type = string(own, THINKING_TYPE);
    if (type.isPresent() && !THINKING_TYPES.contains(type.get())) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + THINKING_TYPE
              + "' must be enabled, adaptive or disabled, was '"
              + type.get()
              + "'");
    }
    // Absent with a budget present means enabled (§9c).
    Optional<String> thinking = type;
    if (type.isEmpty() && budget.isPresent()) {
      thinking = Optional.of(ENABLED);
    }
    if (thinking.filter(ENABLED::equals).isPresent() && budget.isEmpty()) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + THINKING_TYPE
              + "' is enabled and '"
              + PREFIX
              + THINKING_BUDGET
              + "' is not set; the vendor requires a budget for enabled thinking");
    }
    return new Read(
        thinking.filter(value -> !DISABLED.equals(value)),
        budget,
        string(own, CACHE_TTL),
        string(own, SERVICE_TIER));
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
    List<String> supported = KNOWN.stream().sorted().map(name -> PREFIX + name).toList();
    for (String name : VendorProperties.under(merged, PREFIX).keySet()) {
      if (!KNOWN.contains(name)) {
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

  /** The public {@code Features} form, spelled as the properties it means (plan ruling 8). */
  static Map<String, String> of(AnthropicRequests.Features features) {
    Map<String, String> properties = new LinkedHashMap<>();
    if (features.thinking()) {
      properties.put(PREFIX + THINKING_TYPE, ENABLED);
      properties.put(PREFIX + THINKING_BUDGET, Integer.toString(features.thinkingBudget()));
    }
    switch (features.caching()) {
      case OFF -> {
        // No marker.
      }
      case FIVE_MINUTES -> properties.put(PREFIX + CACHE_TTL, "5m");
      case ONE_HOUR -> properties.put(PREFIX + CACHE_TTL, "1h");
    }
    return Collections.unmodifiableMap(properties);
  }

  private static Optional<String> string(Map<String, String> own, String name) {
    return Optional.ofNullable(own.get(name))
        .map(value -> VendorProperties.requireString(PREFIX + name, value));
  }
}
