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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A vendor property an adapter supports: its full prefixed name and the type of its value.
 *
 * <p>Declared once by the adapter that reads it, and used by application code to set it, by the
 * adapter to parse it, and by the warning that lists what an adapter supports. Values still travel
 * as text, so a property set with {@link InferenceConfig#property(VendorProperty, Object)} is the
 * same map entry as one set by name, and a value that does not parse fails naming the property, the
 * value and what it accepts. Text is parsed with plain Java and holds no JSON.
 *
 * <p>Create one with {@link #ofInteger}, {@link #ofBoolean}, {@link #ofFloat} or {@link #ofEnum}.
 *
 * @param <T> the type the value is read as
 */
public interface VendorProperty<T> {

  /** The full name, prefix included: {@code openai.reasoning.effort}. */
  String name();

  /**
   * Reads a value from its text.
   *
   * @throws IllegalArgumentException if the text does not parse, saying what was accepted: {@code
   *     must be an integer, was '12abc'}
   */
  T parse(String text);

  /** The text a value is stored as. */
  String format(T value);

  /**
   * This property's value in {@code properties}, or empty when the map does not carry it.
   *
   * @throws IllegalArgumentException if the value is present and does not parse
   */
  default Optional<T> in(Map<String, String> properties) {
    String text = properties.get(name());
    if (text == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(parse(text));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("property '" + name() + "' " + e.getMessage(), e);
    }
  }

  /** A property whose value is a whole number. */
  static VendorProperty<Integer> ofInteger(String name) {
    return new TypedVendorProperty<>(
        checked(name), Integer::parseInt, String::valueOf, "an integer");
  }

  /** A property whose value is exactly {@code true} or {@code false}. */
  static VendorProperty<Boolean> ofBoolean(String name) {
    return new TypedVendorProperty<>(
        checked(name),
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
  static VendorProperty<Float> ofFloat(String name) {
    return new TypedVendorProperty<>(
        checked(name),
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

  /**
   * A property whose value is one of a fixed set, written as each constant's {@link Enum#name()}
   * and read ignoring case.
   */
  static <E extends Enum<E>> VendorProperty<E> ofEnum(String name, Class<E> type) {
    return ofEnum(name, type, Enum::name);
  }

  /**
   * A property whose value is one of a fixed set, written as {@code formatter} says and read back
   * from that same text ignoring case.
   *
   * @throws IllegalArgumentException if two constants format to the same text ignoring case
   */
  static <E extends Enum<E>> VendorProperty<E> ofEnum(
      String name, Class<E> type, Function<E, String> formatter) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(formatter, "formatter must not be null");
    Map<String, E> byText = new LinkedHashMap<>();
    List<String> texts = new ArrayList<>();
    for (E constant : type.getEnumConstants()) {
      String text = formatter.apply(constant);
      texts.add(text);
      E clash = byText.putIfAbsent(text.toLowerCase(Locale.ROOT), constant);
      if (clash != null) {
        throw new IllegalArgumentException(
            "constants "
                + clash.name()
                + " and "
                + constant.name()
                + " both format as '"
                + text
                + "' ignoring case");
      }
    }
    return new TypedVendorProperty<>(
        checked(name),
        value -> {
          E found = byText.get(value.toLowerCase(Locale.ROOT));
          if (found == null) {
            throw new IllegalArgumentException(value);
          }
          return found;
        },
        formatter,
        texts.stream().collect(Collectors.joining(", ", "one of [", "]")));
  }

  private static String checked(String name) {
    if (name == null || name.isBlank() || name.indexOf('.') <= 0) {
      throw new IllegalArgumentException(
          "a vendor property is named for the adapter that reads it, as in 'openai.x'; was '"
              + name
              + "'");
    }
    return name;
  }
}
