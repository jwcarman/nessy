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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tool's schema as the Responses wire's strict mode needs it: every property listed in {@code
 * required}, the ones that were optional widened to admit {@code null}, {@code oneOf} written as
 * {@code anyOf}, and {@code additionalProperties: false} on every object, {@code $defs} included.
 *
 * <p><b>A private projection of this adapter's own.</b> The generator, the {@code JsonSchema} a
 * tool carries and every other wire are untouched; this reads the JSON text afresh and returns a
 * new document. It is safe because of what binding already does: Jackson turns a JSON {@code null}
 * for an {@code Optional} component into {@code Optional.empty()}, exactly what an omitted property
 * bound to before.
 *
 * <p><b>{@code oneOf} becomes {@code anyOf}.</b> Strict mode accepts {@code anyOf} and not {@code
 * oneOf}. The generator writes {@code oneOf} for an optional record and for a sealed vocabulary,
 * and in both the branches are mutually exclusive, so {@code anyOf} says the same thing.
 *
 * <p><b>A schema strict mode cannot express goes as generated.</b> Strict mode accepts a documented
 * subset of JSON Schema; a schema carrying any keyword outside {@link #STRICT_KEYWORDS} (after
 * {@code oneOf} is read as {@code anyOf}), or an {@code additionalProperties} that is not {@code
 * false}, is returned unchanged with that keyword named, and the caller sends it with {@code
 * strict: false}. The walk covers {@code $defs} and every nested object and branch, so one document
 * never speaks two dialects.
 */
final class OpenAiResponsesSchemas {

  /** What strict mode is documented to accept, with {@code oneOf} read as {@code anyOf}. */
  static final Set<String> STRICT_KEYWORDS =
      Set.of(
          "$schema",
          "$defs",
          "$ref",
          "type",
          "properties",
          "required",
          "additionalProperties",
          "items",
          "anyOf",
          "enum",
          "const",
          "description",
          "title",
          "format",
          "pattern",
          "minimum",
          "maximum",
          "exclusiveMinimum",
          "exclusiveMaximum",
          "multipleOf",
          "minItems",
          "maxItems");

  private static final String NULL = "null";
  private static final String ANY_OF = "anyOf";
  private static final String ONE_OF = "oneOf";

  private OpenAiResponsesSchemas() {}

  /** The schema this wire sends, and whether it may be sent strict. */
  record Projected(Map<String, Object> schema, Optional<String> refusedKeyword) {

    boolean strict() {
      return refusedKeyword.isEmpty();
    }
  }

  static Projected project(String json, JsonMapper mapper) {
    Map<String, Object> generated = mapper.readValue(json, new TypeReference<>() {});
    Optional<String> refused = rootUnion(generated).or(() -> refused(generated));
    return refused.isPresent()
        ? new Projected(generated, refused)
        : new Projected(strict(generated), Optional.empty());
  }

  /** Strict mode requires the root to be an object; anything else cannot be strict. */
  private static Optional<String> rootUnion(Map<?, ?> root) {
    if (root.containsKey(ANY_OF) || root.containsKey(ONE_OF)) {
      return Optional.of((root.containsKey(ANY_OF) ? ANY_OF : ONE_OF) + " at the root");
    }
    if (isObject(root)) {
      return Optional.empty();
    }
    if (root.containsKey("$ref")) {
      return Optional.of("$ref at the root");
    }
    Object type = root.get("type");
    return Optional.of(type == null ? "no type at the root" : "type " + type + " at the root");
  }

  // ---- the check ------------------------------------------------------------------------

  private static Optional<String> refused(Map<?, ?> schema) {
    if (schema.containsKey(ANY_OF) && schema.containsKey(ONE_OF)) {
      return Optional.of(ONE_OF);
    }
    for (Map.Entry<?, ?> entry : schema.entrySet()) {
      String keyword = String.valueOf(entry.getKey());
      Object value = entry.getValue();
      boolean known = STRICT_KEYWORDS.contains(keyword) || ONE_OF.equals(keyword);
      if (!known || ("additionalProperties".equals(keyword) && !Boolean.FALSE.equals(value))) {
        return Optional.of(keyword);
      }
      Optional<String> nested =
          switch (keyword) {
            case "properties", "$defs" ->
                value instanceof Map<?, ?> named ? firstRefused(named.values()) : Optional.empty();
            case ANY_OF, ONE_OF ->
                value instanceof List<?> branches ? firstRefused(branches) : Optional.empty();
            case "items" -> value instanceof Map<?, ?> item ? refused(item) : Optional.empty();
            default -> Optional.empty();
          };
      if (nested.isPresent()) {
        return nested;
      }
    }
    return Optional.empty();
  }

  private static Optional<String> firstRefused(Iterable<?> schemas) {
    for (Object schema : schemas) {
      if (schema instanceof Map<?, ?> map) {
        Optional<String> refused = refused(map);
        if (refused.isPresent()) {
          return refused;
        }
      }
    }
    return Optional.empty();
  }

  // ---- the rewrite ----------------------------------------------------------------------

  private static Map<String, Object> strict(Map<?, ?> schema) {
    Map<String, Object> out = new LinkedHashMap<>();
    schema.forEach(
        (key, value) -> {
          String keyword = String.valueOf(key);
          switch (keyword) {
            case "$defs" -> out.put(keyword, eachStrict(value));
            case "items" -> out.put(keyword, strictOrSelf(value));
            case ANY_OF, ONE_OF -> out.put(ANY_OF, strictBranches(value));
            default -> out.put(keyword, value);
          }
        });
    if (schema.get("properties") instanceof Map<?, ?> properties) {
      Set<String> required = names(schema.get("required"));
      Map<String, Object> rewritten = new LinkedHashMap<>();
      properties.forEach(
          (name, property) -> {
            Object strict = strictOrSelf(property);
            rewritten.put(
                String.valueOf(name),
                required.contains(String.valueOf(name)) ? strict : admittingNull(strict));
          });
      out.put("properties", rewritten);
      out.put("required", new ArrayList<>(rewritten.keySet()));
      out.put("additionalProperties", false);
    } else if (isObject(schema)) {
      out.put("additionalProperties", false);
    }
    return out;
  }

  private static Object strictBranches(Object branches) {
    return branches instanceof List<?> list
        ? list.stream().map(OpenAiResponsesSchemas::strictOrSelf).toList()
        : branches;
  }

  private static Object strictOrSelf(Object schema) {
    return schema instanceof Map<?, ?> map ? strict(map) : schema;
  }

  private static Object eachStrict(Object named) {
    if (!(named instanceof Map<?, ?> map)) {
      return named;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    map.forEach((name, schema) -> out.put(String.valueOf(name), strictOrSelf(schema)));
    return out;
  }

  private static boolean isObject(Map<?, ?> schema) {
    Object type = schema.get("type");
    return "object".equals(type) || (type instanceof List<?> types && types.contains("object"));
  }

  private static Set<String> names(Object required) {
    Set<String> names = new LinkedHashSet<>();
    if (required instanceof List<?> list) {
      list.forEach(name -> names.add(String.valueOf(name)));
    }
    return names;
  }

  /**
   * An optional property, widened so the model can say "nothing" for it: an {@code enum} gains
   * {@code null} among its values (and its type widens with it), a plain {@code type} gains {@code
   * "null"}, and anything else -- a {@code $ref}, a combinator, a {@code const} -- becomes an
   * {@code anyOf} of itself and {@code {"type": "null"}}. A schema that already admits null is left
   * as it was.
   */
  private static Object admittingNull(Object schema) {
    if (!(schema instanceof Map<?, ?> map) || admitsNull(map)) {
      return schema;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    map.forEach((key, value) -> out.put(String.valueOf(key), value));
    if (map.get("enum") instanceof List<?> values) {
      List<Object> widened = new ArrayList<>(values);
      if (!values.contains(null)) {
        widened.add(null);
      }
      out.put("enum", widened);
      widenType(out);
      return out;
    }
    if (!map.containsKey("const") && !map.containsKey("$ref") && widenType(out)) {
      return out;
    }
    return Map.of(ANY_OF, List.of(schema, Map.of("type", NULL)));
  }

  /** Adds {@code "null"} to a {@code type}; false when there was no type to widen. */
  private static boolean widenType(Map<String, Object> schema) {
    Object type = schema.get("type");
    if (type instanceof String single) {
      schema.put("type", NULL.equals(single) ? single : List.of(single, NULL));
      return true;
    }
    if (type instanceof List<?> types) {
      List<Object> widened = new ArrayList<>(types);
      if (!types.contains(NULL)) {
        widened.add(NULL);
      }
      schema.put("type", widened);
      return true;
    }
    return false;
  }

  private static boolean admitsNull(Map<?, ?> schema) {
    if (schema.get(ANY_OF) instanceof List<?> branches) {
      return branches.stream()
          .anyMatch(branch -> branch instanceof Map<?, ?> map && NULL.equals(map.get("type")));
    }
    Object type = schema.get("type");
    boolean typeAdmits =
        NULL.equals(type) || (type instanceof List<?> types && types.contains(NULL));
    boolean enumAdmits = !(schema.get("enum") instanceof List<?> values) || values.contains(null);
    return typeAdmits && enumAdmits && !schema.containsKey("const");
  }
}
