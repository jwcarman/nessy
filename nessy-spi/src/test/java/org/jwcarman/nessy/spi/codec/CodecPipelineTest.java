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
package org.jwcarman.nessy.spi.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;

/**
 * What every stored payload passes through on its way to a database.
 *
 * <p>This is where encryption at rest goes, and it is why the chain is written INTO the bytes
 * rather than into a column: the two consumers have different metadata slots and nothing is common
 * to both, so a payload carries its own header naming what produced it.
 *
 * <p>Nothing wires this into the engine yet. It is tested anyway, because the header is hand-rolled
 * byte handling and the failure it guards against — reading yesterday's bytes with today's
 * configuration — produces garbage rather than an error.
 */
@DisplayName("The transforms a stored payload passes through")
class CodecPipelineTest {

  /** Reverses the bytes. Enough to tell "applied" from "not applied", and its own inverse. */
  private static Codec<byte[]> reversing() {
    return new Codec<>() {
      @Override
      public byte[] encode(byte[] value) {
        byte[] out = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
          out[i] = value[value.length - 1 - i];
        }
        return out;
      }

      @Override
      public byte[] decode(byte[] bytes) {
        return encode(bytes);
      }
    };
  }

  /** Adds a byte going down and removes it coming up, so ORDER is observable. */
  private static Codec<byte[]> stamping(byte stamp) {
    return new Codec<>() {
      @Override
      public byte[] encode(byte[] value) {
        byte[] out = new byte[value.length + 1];
        out[0] = stamp;
        System.arraycopy(value, 0, out, 1, value.length);
        return out;
      }

      @Override
      public byte[] decode(byte[] bytes) {
        byte[] out = new byte[bytes.length - 1];
        System.arraycopy(bytes, 1, out, 0, out.length);
        return out;
      }
    };
  }

  private static CodecPipeline pipelineOf(CodecCustomizer... customizers) {
    return CodecPipeline.of(List.of(customizers));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Nested
  class RoundTripping {

    @Test
    void a_pipeline_that_transforms_nothing_returns_the_payload() {
      CodecPipeline none = CodecPipeline.none();

      assertThat(none.decode(none.encode(bytes("hello")))).isEqualTo(bytes("hello"));
      assertThat(none.transforms()).isEmpty();
    }

    @Test
    void one_transform_round_trips() {
      CodecPipeline pipeline = pipelineOf(chain -> chain.append("reverse", reversing()));

      assertThat(pipeline.decode(pipeline.encode(bytes("hello")))).isEqualTo(bytes("hello"));
    }

    @Test
    void several_transforms_round_trip_in_order() {
      CodecPipeline pipeline =
          pipelineOf(
              chain ->
                  chain
                      .append("stamp-a", stamping((byte) 'a'))
                      .append("reverse", reversing())
                      .append("stamp-b", stamping((byte) 'b')));

      assertThat(pipeline.transforms()).containsExactly("stamp-a", "reverse", "stamp-b");
      assertThat(pipeline.decode(pipeline.encode(bytes("hello")))).isEqualTo(bytes("hello"));
    }

    @Test
    void an_empty_payload_round_trips() {
      CodecPipeline pipeline = pipelineOf(chain -> chain.append("reverse", reversing()));

      assertThat(pipeline.decode(pipeline.encode(new byte[0]))).isEmpty();
    }

    @Test
    @DisplayName("the stored bytes are not the payload, or every test above would be vacuous")
    void a_transform_actually_transforms() {
      CodecPipeline pipeline = pipelineOf(chain -> chain.append("reverse", reversing()));

      assertThat(pipeline.encode(bytes("hello"))).isNotEqualTo(bytes("hello"));
    }
  }

  @Nested
  class ReadingOlderBytes {

    @Test
    @DisplayName("payloads written before a pipeline existed still read")
    void bytes_with_no_header_come_back_untouched() {
      CodecPipeline pipeline = pipelineOf(chain -> chain.append("reverse", reversing()));

      assertThat(pipeline.decode(bytes("written long ago"))).isEqualTo(bytes("written long ago"));
    }

    @Test
    @DisplayName("appending a transform does not orphan what is already written")
    void bytes_are_decoded_by_their_own_header_not_by_todays_configuration() {
      CodecPipeline before = pipelineOf(chain -> chain.append("reverse", reversing()));
      byte[] stored = before.encode(bytes("hello"));

      CodecPipeline after =
          pipelineOf(
              chain -> chain.append("reverse", reversing()).append("stamp", stamping((byte) 'x')));

      assertThat(after.decode(stored)).isEqualTo(bytes("hello"));
    }

    @Test
    @DisplayName("removing a transform while its data survives is an error, not garbage")
    void a_payload_naming_an_unknown_transform_is_refused() {
      CodecPipeline before = pipelineOf(chain -> chain.append("reverse", reversing()));
      byte[] stored = before.encode(bytes("hello"));
      CodecPipeline after = CodecPipeline.none();

      assertThatThrownBy(() -> after.decode(stored))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("reverse");
    }
  }

  @Nested
  class WrappingAFactory {

    @Test
    @DisplayName("every codec the wrapped factory creates already runs through the pipeline")
    void codecs_from_the_wrapped_factory_are_transformed_on_encode() {
      CodecPipeline pipeline = pipelineOf(chain -> chain.append("reverse", reversing()));
      CodecFactory base = Codecs.factory();
      CodecFactory wrapped = pipeline.factoryOver(base);
      Codec<String> plain = base.create(String.class);
      Codec<String> stored = wrapped.create(String.class);

      byte[] plainBytes = plain.encode("hello");
      byte[] storedBytes = stored.encode("hello");

      assertThat(storedBytes).isNotEqualTo(plainBytes);
      assertThat(stored.decode(storedBytes)).isEqualTo("hello");
    }

    @Test
    @DisplayName("a headerless payload still reads correctly through the wrapped codec")
    void bytes_written_before_the_pipeline_existed_still_read_through_the_wrapped_factory() {
      CodecPipeline pipeline = pipelineOf(chain -> chain.append("reverse", reversing()));
      CodecFactory base = Codecs.factory();
      Codec<String> plain = base.create(String.class);
      Codec<String> stored = pipeline.factoryOver(base).create(String.class);

      byte[] plainBytes = plain.encode("hello");

      assertThat(stored.decode(plainBytes)).isEqualTo("hello");
    }
  }

  @Nested
  class BuildingTheChain {

    @Test
    void the_same_transform_name_twice_is_refused() {
      assertThatThrownBy(
              () ->
                  pipelineOf(
                      chain -> chain.append("reverse", reversing()).append("reverse", reversing())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already in the chain");
    }

    @Test
    void a_blank_transform_name_is_refused() {
      assertThatThrownBy(() -> pipelineOf(chain -> chain.append("  ", reversing())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("a name too long for the header is refused when chosen, not when written")
    void an_overlong_transform_name_is_refused() {
      String tooLong = "x".repeat(256);

      assertThatThrownBy(() -> pipelineOf(chain -> chain.append(tooLong, reversing())))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
