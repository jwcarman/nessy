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
import org.jwcarman.nessy.api.VendorProperties;
import org.jwcarman.nessy.api.VendorProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code bedrock.} properties {@link BedrockProperties} declares, from the merged
 * provider and agent-type map. Logs under {@link BedrockProperties}, the class a reader of the log
 * looks for.
 */
final class BedrockPropertyReader {

  static final String PREFIX = "bedrock.";

  private static final Logger log = LoggerFactory.getLogger(BedrockProperties.class);

  private BedrockPropertyReader() {}

  /** The {@code bedrock.} properties once read. */
  record Read(Optional<Float> temperature, Optional<Float> topP) {

    boolean tunesInference() {
      return temperature.isPresent() || topP.isPresent();
    }
  }

  /** The supported names of the merged provider and agent-type map, parsed. Silent. */
  static Read read(Map<String, String> merged) {
    VendorProperties.under(merged, PREFIX);
    return new Read(BedrockProperties.TEMPERATURE.in(merged), BedrockProperties.TOP_P.in(merged));
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
    List<String> supported =
        BedrockProperties.SUPPORTED.stream().map(VendorProperty::name).sorted().toList();
    for (String name : VendorProperties.under(merged, PREFIX).keySet()) {
      if (!supported.contains(PREFIX + name)) {
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
}
