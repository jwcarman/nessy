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
package org.jwcarman.nessy.inference.openai;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code openai.} vendor properties (spec §9a, §9b): the names both OpenAI wires support, and
 * nothing else. A name under the prefix that is not one of them is ignored, and said so once when
 * it is checked. One reading for both wires, so an agent type switching between them keeps its
 * settings.
 */
final class OpenAiProperties {

  static final String PREFIX = "openai.";
  static final String EFFORT = "reasoning.effort";
  static final String SUMMARY = "reasoning.summary";
  static final String STRICT = "tools.strict";
  static final String SERVICE_TIER = "service_tier";

  static final Set<String> KNOWN = Set.of(EFFORT, SUMMARY, STRICT, SERVICE_TIER);

  private static final Logger log = LoggerFactory.getLogger(OpenAiProperties.class);

  private OpenAiProperties() {}

  /**
   * The supported {@code openai.} properties once read.
   *
   * @param strict whether function tools go out strict; always false on the chat wire unless asked
   *     for
   */
  record Read(
      Optional<String> effort,
      Optional<String> summary,
      boolean strict,
      Optional<String> serviceTier) {}

  /** The chat wire's reading of the merged provider and agent-type map (§9a). */
  static Read chat(Map<String, String> merged) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    if (own.containsKey(SUMMARY)) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + SUMMARY
              + "' cannot be sent on the openai-chat wire, which has no reasoning summary;"
              + " the openai-responses wire carries it");
    }
    return read(own);
  }

  /** The Responses wire's reading of the merged provider and agent-type map (§9b). */
  static Read responses(Map<String, String> merged) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Read read = read(own);
    if (own.containsKey(STRICT) && !read.strict()) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + STRICT
              + "' is false, and the openai-responses wire sends function tools strict"
              + " regardless; remove the property, or use the openai-chat wire");
    }
    return read;
  }

  /** The supported names parsed, over a map already filtered to this prefix. Silent. */
  private static Read read(Map<String, String> own) {
    boolean strict =
        own.containsKey(STRICT)
            && VendorProperties.requireBoolean(PREFIX + STRICT, own.get(STRICT));
    return new Read(string(own, EFFORT), string(own, SUMMARY), strict, string(own, SERVICE_TIER));
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
            "NESSY INFERENCE: property '{}{}' is not supported by openai and is ignored;"
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

  /**
   * Another adapter's settings are the design working, so they are named at DEBUG and no louder.
   */
  static void logIgnored(Map<String, String> merged) {
    if (log.isDebugEnabled()) {
      List<String> others = merged.keySet().stream().filter(n -> !n.startsWith(PREFIX)).toList();
      if (!others.isEmpty()) {
        log.debug("NESSY INFERENCE: properties for other adapters, ignored here: {}", others);
      }
    }
  }

  private static Optional<String> string(Map<String, String> own, String name) {
    return Optional.ofNullable(own.get(name))
        .map(value -> VendorProperties.requireString(PREFIX + name, value));
  }
}
