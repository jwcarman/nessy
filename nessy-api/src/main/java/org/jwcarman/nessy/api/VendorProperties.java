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
package org.jwcarman.nessy.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How every adapter reads vendor properties: the entries under its own prefix, and the provider's
 * map under the agent type's. Parsing a supported name into its type is {@link VendorProperty}'s.
 *
 * <p>One class so that no two adapters can disagree about what a prefix is. Every failure is an
 * {@link IllegalArgumentException} naming the property as the user spelled it.
 */
public final class VendorProperties {

  private VendorProperties() {}

  /**
   * The entries under {@code prefix}, with it stripped, in the order given. Entries under other
   * prefixes are left out -- another adapter's settings, not a mistake -- but a name with no prefix
   * at all belongs to nobody and is refused.
   *
   * @param prefix one segment and a dot, as {@code openai.}
   */
  public static Map<String, String> under(Map<String, String> merged, String prefix) {
    Objects.requireNonNull(merged, "merged must not be null");
    requirePrefix(prefix);
    Map<String, String> under = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : merged.entrySet()) {
      String name = entry.getKey();
      if (name.indexOf('.') <= 0) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' has no prefix; a vendor property is named for the adapter that reads it,"
                + " as in 'openai.reasoning.effort'");
      }
      if (name.startsWith(prefix)) {
        under.put(name.substring(prefix.length()), entry.getValue());
      }
    }
    return Collections.unmodifiableMap(under);
  }

  /** The provider's properties, overlaid name by name by the agent type's (spec §7a). */
  public static Map<String, String> merge(
      Map<String, String> provider, Map<String, String> agentType) {
    Map<String, String> merged = new LinkedHashMap<>(provider);
    merged.putAll(agentType);
    return Collections.unmodifiableMap(merged);
  }

  /**
   * The text a property is set to by name: any non-blank string. What it means is checked when the
   * adapter that owns the name reads it.
   */
  public static String requireString(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "property '" + name + "' must be a non-blank string, was '" + value + "'");
    }
    return value;
  }

  private static void requirePrefix(String prefix) {
    Objects.requireNonNull(prefix, "prefix must not be null");
    if (prefix.length() < 2 || prefix.indexOf('.') != prefix.length() - 1) {
      throw new IllegalArgumentException(
          "a prefix is one segment and a dot, as 'openai.'; was '" + prefix + "'");
    }
  }
}
