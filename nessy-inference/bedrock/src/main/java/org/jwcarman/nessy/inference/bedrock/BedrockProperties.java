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
package org.jwcarman.nessy.inference.bedrock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code bedrock.} vendor properties (spec §9e): the three Converse inference settings that are
 * typed on the AWS request, and nothing else. A name under the prefix that is not one of them is
 * ignored, and said so once when it is checked.
 */
final class BedrockProperties {

  static final String PREFIX = "bedrock.";
  static final String TEMPERATURE = "inferenceConfig.temperature";
  static final String TOP_P = "inferenceConfig.topP";
  static final String STOP_SEQUENCES = "inferenceConfig.stopSequences";

  private static final Set<String> KNOWN = Set.of(TEMPERATURE, TOP_P, STOP_SEQUENCES);

  private static final Logger log = LoggerFactory.getLogger(BedrockProperties.class);

  private BedrockProperties() {}

  /** The {@code bedrock.} properties once read. */
  record Read(
      Optional<Float> temperature, Optional<Float> topP, Optional<List<String>> stopSequences) {

    boolean tunesInference() {
      return temperature.isPresent() || topP.isPresent() || stopSequences.isPresent();
    }
  }

  /** The supported names of the merged provider and agent-type map, parsed. Silent. */
  static Read read(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Optional<Float> temperature =
        Optional.ofNullable(own.get(TEMPERATURE)).map(v -> number(TEMPERATURE, v, mapper));
    Optional<Float> topP = Optional.ofNullable(own.get(TOP_P)).map(v -> number(TOP_P, v, mapper));
    Optional<List<String>> stop =
        Optional.ofNullable(own.get(STOP_SEQUENCES)).map(v -> strings(STOP_SEQUENCES, v, mapper));
    return new Read(temperature, topP, stop);
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
            "NESSY INFERENCE: property '{}{}' is not supported by bedrock and is ignored;"
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

  /** The type, never the range: whether 1.7 is a temperature is the vendor's to say. */
  private static float number(String name, String value, JsonMapper mapper) {
    if (VendorProperties.literal(value, mapper) instanceof Number number) {
      return number.floatValue();
    }
    throw new IllegalArgumentException(
        "property '" + PREFIX + name + "' must be a number, was '" + value + "'");
  }

  private static List<String> strings(String name, String value, JsonMapper mapper) {
    if (VendorProperties.literal(value, mapper) instanceof List<?> list
        && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    throw new IllegalArgumentException(
        "property '" + PREFIX + name + "' must be a JSON array of strings, was '" + value + "'");
  }
}
