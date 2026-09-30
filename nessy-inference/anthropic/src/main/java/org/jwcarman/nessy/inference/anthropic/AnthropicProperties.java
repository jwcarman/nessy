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
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code anthropic.} vendor properties (spec §9c): thinking, the cache marker's lifetime and
 * the service tier as known names, the clash table, and the rest passed through.
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

  private static final Set<String> KNOWN =
      Set.of(THINKING_TYPE, THINKING_BUDGET, CACHE_TTL, SERVICE_TIER);

  /** What the typed settings, or the adapter itself, already decide (§9c). */
  private static final Map<String, String> CLASHES =
      Map.of(
          "model", "InferenceConfig.model",
          "max_tokens", "InferenceConfig.maxTokens",
          "messages", "the conversation the engine assembles",
          "system", "the system prompt the harness sends",
          "tools", "the tools the harness binds",
          "tool_choice", "the tool choice the engine makes",
          "output_config", "the answer's shape the harness asks for",
          "stream", "the adapter, which always streams");

  private static final Logger log = LoggerFactory.getLogger(AnthropicProperties.class);

  private AnthropicProperties() {}

  /**
   * The {@code anthropic.} properties once read.
   *
   * @param thinking empty when not thinking (absent, or {@code disabled}); otherwise {@code
   *     enabled}, {@code adaptive}, or a value this adapter sends as written
   * @param budget the thinking budget; always present when {@code thinking} is {@code enabled}
   */
  record Read(
      Optional<String> thinking,
      OptionalInt budget,
      Optional<String> cacheTtl,
      Optional<String> serviceTier,
      Map<String, Object> passThrough) {

    boolean enabled() {
      return thinking.filter(ENABLED::equals).isPresent();
    }
  }

  static Read read(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Map<String, String> clashes = new LinkedHashMap<>(CLASHES);
    claimRoot(own, clashes, "thinking", THINKING_TYPE, THINKING_BUDGET);
    claimRoot(own, clashes, "cache_control", CACHE_TTL);
    VendorProperties.refuseClashes(PREFIX, own, clashes);

    OptionalInt budget =
        own.containsKey(THINKING_BUDGET)
            ? OptionalInt.of(
                VendorProperties.requireInteger(PREFIX + THINKING_BUDGET, own.get(THINKING_BUDGET)))
            : OptionalInt.empty();
    Optional<String> type = string(own, THINKING_TYPE);
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
    Map<String, String> rest = new LinkedHashMap<>(own);
    rest.keySet().removeAll(KNOWN);
    return new Read(
        thinking.filter(value -> !DISABLED.equals(value)),
        budget,
        string(own, CACHE_TTL),
        string(own, SERVICE_TIER),
        VendorProperties.nest(PREFIX, rest, mapper));
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

  /**
   * Plan ruling 6: while a known name builds {@code root}, a pass-through at or under it would be a
   * second statement about the same object, so it joins the clash table naming that known name.
   */
  private static void claimRoot(
      Map<String, String> own, Map<String, String> clashes, String root, String... known) {
    for (String name : known) {
      if (own.containsKey(name)) {
        for (String candidate : own.keySet()) {
          boolean underRoot = candidate.equals(root) || candidate.startsWith(root + ".");
          if (underRoot && !KNOWN.contains(candidate)) {
            clashes.put(candidate, "property '" + PREFIX + name + "'");
          }
        }
        return;
      }
    }
  }

  private static Optional<String> string(Map<String, String> own, String name) {
    return Optional.ofNullable(own.get(name))
        .map(value -> VendorProperties.requireString(PREFIX + name, value));
  }
}
