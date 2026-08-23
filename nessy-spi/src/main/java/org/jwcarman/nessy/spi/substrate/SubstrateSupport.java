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

import com.fasterxml.jackson.databind.ObjectMapper;
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

  /** One freshly constructed, standard {@link ObjectMapper} — this instance's pinned mapper. */
  protected SubstrateSupport() {
    this(new ObjectMapper());
  }

  /**
   * {@code mapper} becomes this instance's pinned mapper — the codec extension point (spec ruling
   * 3): a caller-configured mapper (modules, naming strategy, mix-ins) flows through every codec
   * this substrate's typed views derive, untouched.
   */
  protected SubstrateSupport(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    this.codecs = new MapperCodecFactory(mapper);
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
