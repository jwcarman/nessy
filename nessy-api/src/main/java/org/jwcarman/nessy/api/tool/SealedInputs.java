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
package org.jwcarman.nessy.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The discriminator binder for sealed-interface tool inputs (vocabulary amendment §3, 2026-08-21).
 *
 * <p>{@link Schemas} shapes the sealed interface's wire schema as a {@code oneOf} over its
 * permitted records, each carrying a required const {@code "type"} naming the record. Nessy
 * performs the discriminator binding itself: read {@code "type"}, match it against {@link
 * Class#getPermittedSubclasses()}, bind the remaining properties into that record. A missing or
 * unknown {@code "type"} fails in-band, naming every legal type, so the model reads the error and
 * corrects.
 */
public final class SealedInputs {

  private SealedInputs() {}

  /**
   * True when the class is a sealed interface nessy binds by discriminator. Sealed abstract classes
   * are deliberately excluded — the vocabulary is interface-only, matching {@link Schemas}'
   * sealed-interface schema support.
   */
  public static boolean isSealedInput(Class<?> type) {
    return type.isInterface() && type.isSealed();
  }

  /**
   * Reads "type", matches a permitted record's simple name (case-sensitively — exactly the const
   * written into the schema), binds the remaining properties into that record via the supplied
   * mapper. Missing/unknown "type" → IllegalArgumentException whose message lists the legal type
   * names; a body that fails to bind into the matched record surfaces the mapper's own exception.
   * The returned value is checked by token against the matched permitted class.
   *
   * <p>Defense in depth: if the matched record itself declares a component named {@code "type"},
   * this fails loudly with an IllegalArgumentException naming the record and the collision, rather
   * than silently stripping the caller's "type" value out of the arguments before binding — {@link
   * Schemas} already refuses to generate a schema for such a record, but a caller that hand-builds
   * arguments (bypassing schema generation) must not be able to reach the silent-strip path.
   */
  public static <T> T bind(Class<T> sealedType, JsonNode arguments, ObjectMapper mapper) {
    Class<?>[] permitted = sealedType.getPermittedSubclasses();
    String requestedType = arguments.isObject() ? arguments.path("type").asText(null) : null;
    Class<?> matched = requestedType == null ? null : matching(permitted, requestedType);
    if (matched == null) {
      throw new IllegalArgumentException(
          "unknown \"type\" for "
              + sealedType.getSimpleName()
              + ": "
              + (requestedType == null ? "<missing>" : requestedType)
              + "; expected one of: "
              + legalTypeNames(permitted));
    }
    if (declaresATypeComponent(matched)) {
      throw new IllegalArgumentException(
          "vocabulary record "
              + matched.getSimpleName()
              + " declares a component named \"type\", which collides with the discriminator");
    }
    // matched != null implies arguments.isObject() was true above, so this cast is safe.
    ObjectNode remainder = ((ObjectNode) arguments).deepCopy();
    remainder.remove("type");
    Object bound = mapper.convertValue(remainder, matched);
    return sealedType.cast(bound);
  }

  private static Class<?> matching(Class<?>[] permitted, String requestedType) {
    for (Class<?> candidate : permitted) {
      if (candidate.getSimpleName().equals(requestedType)) {
        return candidate;
      }
    }
    return null;
  }

  private static boolean declaresATypeComponent(Class<?> matched) {
    return Stream.of(matched.getRecordComponents()).anyMatch(c -> c.getName().equals("type"));
  }

  private static String legalTypeNames(Class<?>[] permitted) {
    return Arrays.stream(permitted).map(Class::getSimpleName).collect(Collectors.joining(", "));
  }
}
