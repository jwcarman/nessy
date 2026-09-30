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

import com.google.genai.types.ThinkingConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code gemini.} vendor properties (spec §9d): the three thinking names, and nothing else.
 * Names are spelled as the Gemini REST reference spells them, {@code generationConfig} included,
 * and go through the SDK's typed config. A name under the prefix that is not one of them is
 * ignored, and said so once when it is checked.
 */
final class GeminiProperties {

  static final String PREFIX = "gemini.";
  private static final String THINKING_CONFIG = "generationConfig.thinkingConfig";
  static final String THINKING_BUDGET = THINKING_CONFIG + ".thinkingBudget";
  static final String INCLUDE_THOUGHTS = THINKING_CONFIG + ".includeThoughts";
  static final String THINKING_LEVEL = THINKING_CONFIG + ".thinkingLevel";

  private static final Set<String> KNOWN =
      Set.of(THINKING_BUDGET, INCLUDE_THOUGHTS, THINKING_LEVEL);

  private static final Logger log = LoggerFactory.getLogger(GeminiProperties.class);

  private GeminiProperties() {}

  /** The {@code gemini.} properties once read: a typed thinking config, when any was asked for. */
  record Read(Optional<ThinkingConfig> thinking) {}

  /** The supported names of the merged provider and agent-type map, parsed. Silent. */
  static Read read(Map<String, String> merged) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Optional<ThinkingConfig> thinking = Optional.empty();
    if (own.keySet().stream().anyMatch(KNOWN::contains)) {
      ThinkingConfig.Builder builder = ThinkingConfig.builder();
      if (own.containsKey(THINKING_BUDGET)) {
        builder.thinkingBudget(
            VendorProperties.requireInteger(PREFIX + THINKING_BUDGET, own.get(THINKING_BUDGET)));
      }
      if (own.containsKey(INCLUDE_THOUGHTS)) {
        builder.includeThoughts(
            VendorProperties.requireBoolean(PREFIX + INCLUDE_THOUGHTS, own.get(INCLUDE_THOUGHTS)));
      }
      if (own.containsKey(THINKING_LEVEL)) {
        builder.thinkingLevel(
            VendorProperties.requireString(PREFIX + THINKING_LEVEL, own.get(THINKING_LEVEL)));
      }
      thinking = Optional.of(builder.build());
    }
    return new Read(thinking);
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
            "NESSY INFERENCE: property '{}{}' is not supported by gemini and is ignored;"
                + " supported: {}",
            PREFIX,
            name,
            supported);
      }
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
}
