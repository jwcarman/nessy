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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code gemini.} vendor properties (spec §9d). Names are spelled as the Gemini REST reference
 * spells them, {@code generationConfig} included, so a known name reads like the pass-through
 * beside it. The three thinking names go through the SDK's typed config, so they cannot depend on
 * how {@code extraBody} merges; everything else under the prefix is sent through {@code extraBody}.
 */
final class GeminiProperties {

  static final String PREFIX = "gemini.";
  private static final String THINKING_CONFIG = "generationConfig.thinkingConfig";
  static final String THINKING_BUDGET = THINKING_CONFIG + ".thinkingBudget";
  static final String INCLUDE_THOUGHTS = THINKING_CONFIG + ".includeThoughts";
  static final String THINKING_LEVEL = THINKING_CONFIG + ".thinkingLevel";

  private static final Set<String> KNOWN =
      Set.of(THINKING_BUDGET, INCLUDE_THOUGHTS, THINKING_LEVEL);

  private static final String SHAPE = "the answer's shape the harness asks for";

  /** What the typed settings, or the adapter itself, already decide (§9d). */
  private static final Map<String, String> CLASHES =
      Map.of(
          "contents", "the conversation the engine assembles",
          "systemInstruction", "the system prompt the harness sends",
          "tools", "the tools the harness binds",
          "toolConfig", "the tool choice the engine makes",
          "generationConfig.maxOutputTokens", "InferenceConfig.maxTokens",
          "generationConfig.responseMimeType", SHAPE,
          "generationConfig.responseJsonSchema", SHAPE,
          "generationConfig.responseSchema", SHAPE);

  private static final Logger log = LoggerFactory.getLogger(GeminiProperties.class);

  private GeminiProperties() {}

  /** The {@code gemini.} properties once read: a typed thinking config, and the extra body. */
  record Read(Optional<ThinkingConfig> thinking, Map<String, Object> passThrough) {}

  static Read read(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Map<String, String> clashes = new LinkedHashMap<>(CLASHES);
    String known = own.keySet().stream().filter(KNOWN::contains).findFirst().orElse(null);
    if (known != null) {
      // Plan ruling 6: the typed thinking config owns its object.
      for (String name : own.keySet()) {
        boolean under = name.equals(THINKING_CONFIG) || name.startsWith(THINKING_CONFIG + ".");
        if (under && !KNOWN.contains(name)) {
          clashes.put(name, "property '" + PREFIX + known + "'");
        }
      }
    }
    Map<String, String> rest = new LinkedHashMap<>(own);
    rest.keySet().removeAll(KNOWN);
    VendorProperties.refuseClashes(PREFIX, rest, clashes);
    Optional<ThinkingConfig> thinking = Optional.empty();
    if (known != null) {
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
    return new Read(thinking, VendorProperties.nest(PREFIX, rest, mapper));
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
