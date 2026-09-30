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
package org.jwcarman.nessy.vendor;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * How every adapter reads vendor properties: the entries under its own prefix, the provider's map
 * under the agent type's, each value as a JSON literal where it parses as one, dotted names as
 * nested objects, and the names a typed setting already decides refused.
 *
 * <p>One class so that eight adapters cannot come to disagree about what {@code true} means. An
 * adapter keeps its own prefix, its known names and its clash table; everything mechanical is here.
 * Every failure is an {@link IllegalArgumentException} naming the property as the user spelled it.
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
                + " as in 'openai.temperature'");
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
   * The value as the JSON literal it spells -- a map, a list, a string, a number, a boolean or
   * {@code null} -- or, when it is not JSON, the text itself: {@code high} is the string {@code
   * "high"}. The whole value must parse, whatever the mapper was configured to tolerate: {@code
   * 12abc} is a string, not the number twelve, and an array is always a {@code List}. Numbers
   * follow the mapper's own floating-point setting.
   */
  public static Object literal(String value, JsonMapper mapper) {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    try {
      return mapper
          .readerFor(Object.class)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .without(DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY)
          .readValue(value);
    } catch (JacksonException notJson) {
      // Not JSON, so it is the text it says.
      return value;
    }
  }

  /**
   * The names (prefix already stripped) as one tree: every dot nests an object, two paths that meet
   * are merged, and a value where another name needs an object is refused naming both.
   *
   * @param prefix what was stripped, so a message names the property as the user spelled it
   */
  public static Map<String, Object> nest(
      String prefix, Map<String, String> flat, JsonMapper mapper) {
    requirePrefix(prefix);
    Map<String, Object> root = new LinkedHashMap<>();
    Map<String, Map<String, Object>> objects = new HashMap<>();
    Map<String, String> openedBy = new HashMap<>();
    Map<String, String> values = new HashMap<>();
    objects.put("", root);
    for (Map.Entry<String, String> entry : flat.entrySet()) {
      String name = entry.getKey();
      String property = prefix + name;
      String[] segments = name.split("\\.", -1);
      for (String segment : segments) {
        if (segment.isEmpty()) {
          throw new IllegalArgumentException("property '" + property + "' has an empty segment");
        }
      }
      String parent = "";
      for (int i = 0; i < segments.length - 1; i++) {
        String path = parent.isEmpty() ? segments[i] : parent + "." + segments[i];
        if (values.containsKey(path)) {
          throw meeting(values.get(path), property, prefix + path);
        }
        if (!objects.containsKey(path)) {
          Map<String, Object> child = new LinkedHashMap<>();
          objects.get(parent).put(segments[i], child);
          objects.put(path, child);
          openedBy.put(path, property);
        }
        parent = path;
      }
      if (objects.containsKey(name)) {
        throw meeting(property, openedBy.get(name), property);
      }
      objects.get(parent).put(segments[segments.length - 1], literal(entry.getValue(), mapper));
      values.put(name, property);
    }
    return root;
  }

  /**
   * Refuses any entry the adapter's table says a typed setting, or the adapter itself, already
   * decides (spec §7b): the clash key itself, a name inside it ({@code text.verbosity} beside
   * {@code text}), or a name that would replace it whole ({@code generationConfig} beside {@code
   * generationConfig.maxOutputTokens}). An exact match is checked first; the table is then scanned
   * in sorted order so the first refusal never depends on the table's own iteration order.
   *
   * <p>Pass only the names the adapter passes through: a known name it parses itself, such as
   * {@code tools.strict} under {@code tools}, is not a clash.
   *
   * @param clashTable a stripped name, and what decides it
   */
  public static void refuseClashes(
      String prefix, Map<String, String> underPrefix, Map<String, String> clashTable) {
    for (String name : underPrefix.keySet()) {
      String decidedBy = clashTable.get(name);
      if (decidedBy != null) {
        throw new IllegalArgumentException(
            "property '"
                + prefix
                + name
                + "' names what "
                + decidedBy
                + " already decides; remove the property");
      }
    }
    for (String key : new TreeSet<>(clashTable.keySet())) {
      for (String name : underPrefix.keySet()) {
        if (name.startsWith(key + ".")) {
          throw new IllegalArgumentException(
              "property '"
                  + prefix
                  + name
                  + "' sets a field inside '"
                  + prefix
                  + key
                  + "', which "
                  + clashTable.get(key)
                  + " already decides; remove the property");
        }
        if (key.startsWith(name + ".")) {
          throw new IllegalArgumentException(
              "property '"
                  + prefix
                  + name
                  + "' would replace '"
                  + prefix
                  + key
                  + "', which "
                  + clashTable.get(key)
                  + " already decides; set the fields one by one");
        }
      }
    }
  }

  /** A known name that takes an integer (spec §8b). */
  public static int requireInteger(String name, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "property '" + name + "' must be an integer, was '" + value + "'", e);
    }
  }

  /** A known name that takes a boolean, spelled as JSON spells one. */
  public static boolean requireBoolean(String name, String value) {
    if ("true".equals(value)) {
      return true;
    }
    if ("false".equals(value)) {
      return false;
    }
    throw new IllegalArgumentException(
        "property '" + name + "' must be true or false, was '" + value + "'");
  }

  /**
   * A known name that takes a string: any non-blank text, because the vocabulary is the vendor's.
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

  private static IllegalArgumentException meeting(String first, String second, String path) {
    return new IllegalArgumentException(
        "properties '"
            + first
            + "' and '"
            + second
            + "' cannot both be sent: one sets '"
            + path
            + "' whole and the other sets a field inside it");
  }
}
