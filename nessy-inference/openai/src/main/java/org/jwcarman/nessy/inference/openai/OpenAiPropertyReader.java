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
import org.jwcarman.nessy.api.VendorProperties;
import org.jwcarman.nessy.api.VendorProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code openai.} properties {@link OpenAiProperties} declares, from the merged provider
 * and agent-type map: one reading for both wires, so an agent type switching between them keeps its
 * settings. Logs under {@link OpenAiProperties}, the class a reader of the log looks for.
 */
final class OpenAiPropertyReader {

  static final String PREFIX = "openai.";

  private static final Logger log = LoggerFactory.getLogger(OpenAiProperties.class);

  private OpenAiPropertyReader() {}

  /**
   * The supported {@code openai.} properties once read.
   *
   * @param strict whether function tools go out strict; always false on the chat wire unless asked
   *     for
   */
  record Read(
      Optional<OpenAiReasoningEffort> effort,
      Optional<OpenAiReasoningSummary> summary,
      boolean strict,
      Optional<OpenAiServiceTier> serviceTier) {}

  /** The chat wire's reading of the merged provider and agent-type map (§9a). */
  static Read chat(Map<String, String> merged) {
    Read read = read(merged);
    if (read.summary().isPresent()) {
      throw new IllegalArgumentException(
          "property '"
              + OpenAiProperties.REASONING_SUMMARY.name()
              + "' cannot be sent on the openai-chat wire, which has no reasoning summary;"
              + " the openai-responses wire carries it");
    }
    if (read.serviceTier().filter(OpenAiServiceTier.ULTRAFAST::equals).isPresent()) {
      throw new IllegalArgumentException(
          "property '"
              + OpenAiProperties.SERVICE_TIER.name()
              + "' cannot be 'ultrafast' on the openai-chat wire;"
              + " the openai-responses wire carries it");
    }
    return read;
  }

  /** The Responses wire's reading of the merged provider and agent-type map (§9b). */
  static Read responses(Map<String, String> merged) {
    Read read = read(merged);
    if (OpenAiProperties.TOOLS_STRICT.in(merged).filter(strict -> !strict).isPresent()) {
      throw new IllegalArgumentException(
          "property '"
              + OpenAiProperties.TOOLS_STRICT.name()
              + "' is false, and the openai-responses wire sends function tools strict"
              + " regardless; remove the property, or use the openai-chat wire");
    }
    return read;
  }

  /** The supported names parsed. A name with no prefix is refused first. Silent. */
  private static Read read(Map<String, String> merged) {
    VendorProperties.under(merged, PREFIX);
    return new Read(
        OpenAiProperties.REASONING_EFFORT.in(merged),
        OpenAiProperties.REASONING_SUMMARY.in(merged),
        OpenAiProperties.TOOLS_STRICT.in(merged).orElse(false),
        OpenAiProperties.SERVICE_TIER.in(merged));
  }

  /**
   * Says, once per name, that a property under this prefix is not one this adapter supports and is
   * ignored. Called when a property set is first checked (a provider's build, a harness's
   * validate), never per request.
   */
  static void warnUnsupported(Map<String, String> merged) {
    List<String> supported =
        OpenAiProperties.SUPPORTED.stream().map(VendorProperty::name).sorted().toList();
    for (String name : VendorProperties.under(merged, PREFIX).keySet()) {
      if (!supported.contains(PREFIX + name)) {
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
}
