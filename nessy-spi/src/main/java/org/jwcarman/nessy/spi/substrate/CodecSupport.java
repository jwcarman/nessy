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
package org.jwcarman.nessy.spi.substrate;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Objects;

/**
 * Package-private implementations backing {@link Codec}'s default and static factory methods. Kept
 * out of the interface body (and out of the public API entirely — this class and every nested type
 * here are package-private) so the published {@code nessy-spi} jar exposes exactly one new public
 * type for this seam: {@link Codec} itself.
 */
final class CodecSupport {

  private CodecSupport() {}

  static <T> Codec<T> then(Codec<T> first, Codec<byte[]> next) {
    return new ThenCodec<>(first, next);
  }

  /**
   * One plain tolerant-binding path (substrate spec §3, §7 repeal): a sealed interface {@code type}
   * must already carry Jackson polymorphism, as {@code mapper} itself would see it — {@link
   * #polymorphicTypeInfo} asks the mapper's own {@link AnnotationIntrospector} rather than
   * reflecting on {@code @JsonTypeInfo} directly, so a vocabulary configured entirely through
   * {@code mapper.addMixIn(...)} (no annotation on the class itself) is recognized too; how a
   * caller attaches the annotations is the caller's business. Plain Jackson would otherwise ENCODE
   * an unannotated sealed value with no discriminator at all, producing bytes nothing could ever
   * decode back — rejected here, at {@code Codec.json} call time, before any write, the same
   * construction-time posture {@link org.jwcarman.nessy.api.tool.Schemas} takes for the identical
   * reason (the message below mirrors its wording).
   */
  static <T> Codec<T> json(ObjectMapper mapper, Class<T> type) {
    JsonTypeInfo.Value polymorphicTypeInfo = polymorphicTypeInfo(mapper, type);
    requirePolymorphicTypeInfoIfSealed(type, polymorphicTypeInfo);
    return new PlainJsonCodec<>(mapper, type, polymorphicTypeInfo);
  }

  /**
   * {@code mapper}'s own answer to "does {@code type} carry Jackson polymorphism info" — via {@code
   * DeserializationConfig.introspectClassAnnotations(type)} (which folds in any mix-in {@code
   * mapper} has registered for {@code type}) and {@link
   * AnnotationIntrospector#findPolymorphicTypeInfo}, the same lookup Jackson's own (de)serializer
   * construction uses. Deliberately not {@code mapper.canSerialize}/{@code canDeserialize}: both
   * are shallow for this question — verified empirically (a completely unannotated sealed
   * interface, one permitted record, no mix-in registered): {@code canSerialize} correctly answers
   * {@code false}, but {@code canDeserialize} answers {@code true} anyway, because Jackson can
   * still build an {@code AbstractDeserializer} placeholder for the bare interface even though no
   * discriminator would ever be written on encode and nothing could ever be read back). Returns
   * {@code null} when {@code type} carries no polymorphism info by the mapper's own lights.
   */
  private static JsonTypeInfo.Value polymorphicTypeInfo(ObjectMapper mapper, Class<?> type) {
    DeserializationConfig config = mapper.getDeserializationConfig();
    var annotatedClass = config.introspectClassAnnotations(type).getClassInfo();
    return config.getAnnotationIntrospector().findPolymorphicTypeInfo(config, annotatedClass);
  }

  private static void requirePolymorphicTypeInfoIfSealed(
      Class<?> type, JsonTypeInfo.Value polymorphicTypeInfo) {
    if (!(type.isInterface() && type.isSealed()) || polymorphicTypeInfo != null) {
      return;
    }
    throw new IllegalArgumentException(
        "sealed interface "
            + type.getSimpleName()
            + " is bound through Codec.json but carries no Jackson polymorphism annotations; add"
            + " @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = \"type\") and @JsonSubTypes"
            + " naming each permitted record (e.g. @JsonSubTypes.Type(value = Restart.class,"
            + " name = \"Restart\")) so encoding writes a discriminator and decoding can read it"
            + " back — directly on the type or via a mapper mix-in");
  }

  /** {@link Codec#then(Codec)}'s composed codec. */
  private static final class ThenCodec<T> implements Codec<T> {

    private final Codec<T> first;
    private final Codec<byte[]> next;

    private ThenCodec(Codec<T> first, Codec<byte[]> next) {
      this.first = first;
      this.next = next;
    }

    @Override
    public byte[] encode(T value) {
      return next.encode(first.encode(value));
    }

    @Override
    public T decode(byte[] bytes) {
      return first.decode(next.decode(bytes));
    }
  }

  /**
   * {@code Codec.json} for any type bound via {@code readValue}/{@code writeValueAsBytes}: plain
   * records bind directly, and a sealed interface type (already guaranteed by {@link #json} to
   * carry Jackson polymorphism info, by the mapper's own lights) defers wholly to Jackson's own
   * polymorphic machinery — the discriminator property and its per-record values come from the
   * annotations (or mix-in), exactly as Jackson would bind them anywhere else.
   *
   * <p>Jackson does not itself reject a permitted record that declares its own component sharing
   * the discriminator's property name (verified empirically: encoding silently writes a duplicate
   * key, decoding silently lets the record's own value win over the discriminator's) — {@link
   * #requireNoTypeComponentCollision} catches that offense before any write, naming the record and
   * the property, rather than letting the corruption through.
   */
  private static final class PlainJsonCodec<T> implements Codec<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;
    private final String collisionProperty;

    private PlainJsonCodec(
        ObjectMapper mapper, Class<T> type, JsonTypeInfo.Value polymorphicTypeInfo) {
      this.mapper = mapper;
      this.type = type;
      this.collisionProperty = discriminatorPropertyIfCollisionPossible(type, polymorphicTypeInfo);
    }

    /**
     * The discriminator's property name when {@code type} is a sealed interface carrying
     * polymorphism info using {@link JsonTypeInfo.As#PROPERTY} inclusion (the convention used
     * throughout this codebase) — the only inclusion style where a permitted record's own component
     * can collide with the discriminator by sharing its name. {@code null} otherwise, meaning no
     * collision is possible so {@link #requireNoTypeComponentCollision} is a no-op.
     */
    private static String discriminatorPropertyIfCollisionPossible(
        Class<?> type, JsonTypeInfo.Value polymorphicTypeInfo) {
      if (!(type.isInterface() && type.isSealed()) || polymorphicTypeInfo == null) {
        return null;
      }
      if (polymorphicTypeInfo.getInclusionType() != JsonTypeInfo.As.PROPERTY) {
        return null;
      }
      String property = polymorphicTypeInfo.getPropertyName();
      return property == null || property.isEmpty() ? null : property;
    }

    @Override
    public byte[] encode(T value) {
      Objects.requireNonNull(value, "value must not be null");
      requireNoTypeComponentCollision(value);
      try {
        return mapper.writeValueAsBytes(value);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(
            "failed to encode " + type.getSimpleName() + " to JSON: " + e.getMessage(), e);
      }
    }

    private void requireNoTypeComponentCollision(T value) {
      if (collisionProperty == null) {
        return;
      }
      Class<?> runtimeType = value.getClass();
      if (!runtimeType.isRecord()) {
        return;
      }
      for (RecordComponent component : runtimeType.getRecordComponents()) {
        if (component.getName().equals(collisionProperty)) {
          throw new IllegalArgumentException(
              "vocabulary record "
                  + runtimeType.getSimpleName()
                  + " declares a component named \""
                  + collisionProperty
                  + "\", which collides with the discriminator "
                  + type.getSimpleName()
                  + "'s polymorphism info injects (Jackson would silently duplicate it on encode"
                  + " and let it win over the discriminator on decode)");
        }
      }
    }

    @Override
    public T decode(byte[] bytes) {
      Objects.requireNonNull(bytes, "bytes must not be null");
      try {
        return mapper.readValue(bytes, type);
      } catch (IOException e) {
        throw new IllegalArgumentException(
            "failed to decode " + type.getSimpleName() + " from JSON: " + e.getMessage(), e);
      }
    }
  }
}
