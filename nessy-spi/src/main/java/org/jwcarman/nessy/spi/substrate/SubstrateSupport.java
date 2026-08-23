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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Objects;

/**
 * The support base a {@link Substrate} implementation extends to satisfy {@link Substrate#codecs()}
 * (typed-stores spec §1 ruling 3): one pinned, standard {@link ObjectMapper} per substrate instance
 * (statics-die law — no shared static mapper), wrapped as a {@link CodecFactory} via {@link
 * Codec#json(ObjectMapper, Class)}. Overriding the mapper at construction — {@link
 * #SubstrateSupport(ObjectMapper)} — IS the codec extension point (spec ruling 3): the parked
 * {@code .backlogCodec} config door and every other per-feature codec-threading seam retire into
 * this one override.
 *
 * <p><b>Mappers-threaded law: copy-and-pin at the boundary.</b> {@link #copyAndPin(ObjectMapper)}
 * is the single source of truth for the format-critical settings a stored document's wire format
 * cannot float on (lower-camel property naming, tolerant reads, {@code ALWAYS} inclusion, no
 * default typing, no root wrapping — see its own javadoc for the full list and why each one is
 * pinned). BOTH constructors here route through it — the default, standard mapper and any
 * caller-supplied one alike — so {@link #codecs()} is stored-format-safe regardless of who
 * constructed the substrate or what mapper they handed it; a caller-supplied substrate can never
 * float the stored format merely by skipping a pin call of its own.
 *
 * <p>Also holds the package-private message/format constants shared across {@link Substrate}'s
 * sibling nested record types ({@code Document}, {@code Entry}, {@code Op.WriteDocument}, {@code
 * Op.AppendEntry}) — wire/error text, not published API, kept off the {@link Substrate} interface
 * body since an interface field is implicitly {@code public static final}.
 */
public abstract class SubstrateSupport {

  /** Shared {@link NullPointerException} message for a null {@code payload} argument. */
  static final String PAYLOAD_NULL_MESSAGE = "payload must not be null";

  /** Shared {@code toString()} field-separator label for a record's {@code payload} byte count. */
  static final String PAYLOAD_BYTES_LABEL = ", payloadBytes=";

  private final CodecFactory codecs;

  /** One freshly constructed, standard {@link ObjectMapper} — pinned — this instance's mapper. */
  protected SubstrateSupport() {
    this(new ObjectMapper());
  }

  /**
   * {@code mapper}, copy-and-pinned (see {@link #copyAndPin(ObjectMapper)}), becomes this
   * instance's pinned mapper — the codec extension point (spec ruling 3): a caller-configured
   * mapper (modules, naming strategy, mix-ins) flows through every codec this substrate's typed
   * views derive, untouched, except for the format-critical knobs the pin always applies.
   */
  protected SubstrateSupport(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    this.codecs = new MapperCodecFactory(copyAndPin(mapper));
  }

  /**
   * {@code mapper.copy()} with the format-critical settings pinned (spec §7): lower-camel property
   * naming, tolerant reads (unknown fields ignored), no default typing. User-registered modules and
   * serializers survive the copy — only the wire-format knobs are pinned, since the stored format
   * is a compatibility surface and cannot float on presentation preferences. {@code
   * FAIL_ON_EMPTY_BEANS} is also disabled on the copy so a zero-component wire record (e.g. an
   * outcome variant with no payload) still renders rather than throwing.
   *
   * <p>Serialization inclusion is pinned to {@code ALWAYS}: a caller mapper configured for {@code
   * NON_EMPTY} (or any other omit-if-default policy) would otherwise survive the copy and drop
   * empty or absent fields from the wire — a recipe whose document round-trips through its own
   * canonical constructor (spec §7) then fails to parse the very document it just wrote. {@code
   * WRITE_EMPTY_JSON_ARRAYS} is pinned {@code true} for the same reason: a per-type {@code
   * configOverride} on the caller's mapper can still ask for {@code NON_EMPTY} on a specific class,
   * and the pin alone does not out-rank that override — a wire record with a collection field that
   * must always render carries its own {@code @JsonInclude(ALWAYS)} to close that route. Root
   * wrapping is pinned off both directions for the same reason: it is a presentation preference,
   * not a format the stored bytes can float on.
   *
   * <p>What the pin does <em>not</em> defend against: a caller mapper with {@code
   * MapperFeature.USE_ANNOTATIONS} disabled, a caller-installed {@code setVisibility} override, or
   * {@code WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED} enabled. These disable binding wholesale rather than
   * merely omitting empty values, so they are not format-critical settings this pin can restore —
   * they fail loudly at read time instead, and that failure is the caller's own foot.
   *
   * <p>The single source of truth for this knob list (mappers-threaded law): the host module's own
   * {@code Codecs.copyAndPin(ObjectMapper)} delegates here rather than carrying its own copy, so a
   * stored document's format-critical settings are pinned identically whether the mapper reaches a
   * recipe through a harness's {@code .objectMapper(ObjectMapper)} or through a substrate's own
   * {@link #codecs()}.
   */
  public static ObjectMapper copyAndPin(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return mapper
        .copy()
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .setPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
        .configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS, true)
        .configure(SerializationFeature.WRAP_ROOT_VALUE, false)
        .configure(DeserializationFeature.UNWRAP_ROOT_VALUE, false)
        .deactivateDefaultTyping();
  }

  /** This instance's {@link CodecFactory} — satisfies {@link Substrate#codecs()}. */
  public final CodecFactory codecs() {
    return codecs;
  }

  private static final class MapperCodecFactory implements CodecFactory {

    private final ObjectMapper mapper;

    private MapperCodecFactory(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    @Override
    public <T> Codec<T> codec(Class<T> type) {
      return Codec.json(mapper, type);
    }
  }
}
