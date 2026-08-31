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
package org.jwcarman.nessy.engine;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.jwcarman.codec.spi.Codec;

/**
 * A {@link Codec} over one of the engine's own mappers.
 *
 * <p>Exists because a substrate's own codec factory carries ITS mapper, which knows nothing of the
 * bindings the engine adds — the {@code Message} discriminator most of all. A transcript encoded
 * through the substrate's factory writes messages with no role on them and fails to read them back.
 * That is not a hypothetical: it is what the transcript test caught.
 *
 * <p>The trade is that anything encoded here does NOT pass through the substrate's codec pipeline,
 * so transforms configured there — compression, encryption — do not apply. Composing them back on
 * is {@code codec.andThen(pipeline)} once a harness has a pipeline to hand over.
 */
final class JsonCodec {

  private JsonCodec() {}

  static <T> Codec<T> of(ObjectMapper mapper, Class<T> type) {
    return new Codec<>() {
      @Override
      public byte[] encode(T value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
          throw new UncheckedIOException("could not write " + type.getSimpleName(), e);
        }
      }

      @Override
      public T decode(byte[] bytes) {
        try {
          return mapper.readValue(bytes, type);
        } catch (IOException e) {
          throw new UncheckedIOException("could not read " + type.getSimpleName(), e);
        }
      }
    };
  }

  /**
   * A codec for a list of a polymorphic type.
   *
   * <p>Needed because what a turn holds mid-exchange is CONTENT — the blocks the model asked with —
   * and not a message: the message cannot exist until its results do. Jackson needs the element
   * type named to resolve the discriminator on the way back in, which a raw {@code List.class}
   * would not carry.
   */
  static <T> Codec<List<T>> ofList(ObjectMapper mapper, Class<T> element) {
    JavaType type = mapper.getTypeFactory().constructCollectionType(List.class, element);
    return new Codec<>() {
      @Override
      public byte[] encode(List<T> value) {
        try {
          return mapper.writerFor(type).writeValueAsBytes(value);
        } catch (IOException e) {
          throw new UncheckedIOException("could not write a list of " + element.getSimpleName(), e);
        }
      }

      @Override
      public List<T> decode(byte[] bytes) {
        try {
          return mapper.readerFor(type).readValue(bytes);
        } catch (IOException e) {
          throw new UncheckedIOException("could not read a list of " + element.getSimpleName(), e);
        }
      }
    };
  }
}
