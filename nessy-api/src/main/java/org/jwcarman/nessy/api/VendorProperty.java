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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A vendor property an adapter supports: its full prefixed name and the type of its value.
 *
 * <p>Declared once by the adapter that reads it, and used by application code to set it, by the
 * adapter to parse it, and by the warning that lists what an adapter supports. Values still travel
 * as text, so a property set with {@link InferenceConfig#property(VendorProperty, Object)} is the
 * same map entry as one set by name, and a value that does not parse fails naming the property, the
 * value and what it accepts. Equal by name.
 *
 * @param <T> the type the value is read as
 */
public final class VendorProperty<T> {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String name;
  private final Function<String, T> parse;
  private final Function<T, String> format;
  private final String accepted;

  private VendorProperty(
      String name, Function<String, T> parse, Function<T, String> format, String accepted) {
    if (name == null || name.isBlank() || name.indexOf('.') <= 0) {
      throw new IllegalArgumentException(
          "a vendor property is named for the adapter that reads it, as in 'openai.x'; was '"
              + name
              + "'");
    }
    this.name = name;
    this.parse = parse;
    this.format = format;
    this.accepted = accepted;
  }

  /** A property whose value is a whole number. */
  public static VendorProperty<Integer> ofInteger(String name) {
    return new VendorProperty<>(name, Integer::parseInt, String::valueOf, "an integer");
  }

  /** A property whose value is exactly {@code true} or {@code false}. */
  public static VendorProperty<Boolean> ofBoolean(String name) {
    return new VendorProperty<>(
        name,
        value -> {
          if ("true".equals(value)) {
            return true;
          }
          if ("false".equals(value)) {
            return false;
          }
          throw new NumberFormatException(value);
        },
        String::valueOf,
        "true or false");
  }

  /** A property whose value is a finite number. */
  public static VendorProperty<Float> ofFloat(String name) {
    return new VendorProperty<>(
        name,
        value -> {
          float parsed = Float.parseFloat(value);
          if (!Float.isFinite(parsed)) {
            throw new NumberFormatException(value);
          }
          return parsed;
        },
        String::valueOf,
        "a number");
  }

  /** A property whose value is a JSON array of strings. */
  public static VendorProperty<List<String>> ofStrings(String name) {
    return new VendorProperty<>(
        name,
        VendorProperty::readStrings,
        list -> JSON.writeValueAsString(list),
        "a JSON array of strings");
  }

  /**
   * A property whose value is one of a fixed set, each spelled as the vendor spells it.
   *
   * @param spelling the text of a constant, matched exactly when reading
   */
  public static <E extends Enum<E>> VendorProperty<E> ofEnum(
      String name, Class<E> type, Function<E, String> spelling) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(spelling, "spelling must not be null");
    List<E> constants = Arrays.asList(type.getEnumConstants());
    String spellings =
        constants.stream().map(spelling).collect(Collectors.joining(", ", "one of [", "]"));
    return new VendorProperty<>(
        name,
        value ->
            constants.stream()
                .filter(constant -> spelling.apply(constant).equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(value)),
        spelling,
        spellings);
  }

  /** The full name, prefix included: {@code openai.reasoning.effort}. */
  public String name() {
    return name;
  }

  /** The text a value is stored as. */
  public String format(T value) {
    Objects.requireNonNull(value, "value must not be null");
    return format.apply(value);
  }

  /**
   * This property's value in {@code properties}, or empty when the map does not carry it.
   *
   * @throws IllegalArgumentException if the value is present and does not parse
   */
  public Optional<T> in(Map<String, String> properties) {
    String text = properties.get(name);
    if (text == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(parse.apply(text));
    } catch (IllegalArgumentException | JacksonException e) {
      throw new IllegalArgumentException(
          "property '" + name + "' must be " + accepted + ", was '" + text + "'", e);
    }
  }

  private static List<String> readStrings(String value) {
    Object read;
    try {
      read =
          JSON.readerFor(Object.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .without(DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY)
              .readValue(value);
    } catch (JacksonException notJson) {
      throw new IllegalArgumentException(value, notJson);
    }
    if (read instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    throw new IllegalArgumentException(value);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof VendorProperty<?> that && name.equals(that.name);
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public String toString() {
    return name;
  }
}
