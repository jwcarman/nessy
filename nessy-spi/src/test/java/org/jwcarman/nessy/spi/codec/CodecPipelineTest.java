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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The pipeline's job is surviving configuration changes. Round-tripping is the easy half; the tests
 * that matter are the two ordinary edits that would corrupt data silently without a header.
 */
class CodecPipelineTest {

  private static final byte[] PAYLOAD = "the disk is full".getBytes(StandardCharsets.UTF_8);

  /** Reverses the bytes — its own inverse, and obvious in a failure message. */
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
      public byte[] decode(byte[] value) {
        return encode(value);
      }
    };
  }

  /** Appends a sentinel byte, so applying it twice is detectable. */
  private static Codec<byte[]> tagging(byte tag) {
    return new Codec<>() {
      @Override
      public byte[] encode(byte[] value) {
        byte[] out = new byte[value.length + 1];
        System.arraycopy(value, 0, out, 0, value.length);
        out[value.length] = tag;
        return out;
      }

      @Override
      public byte[] decode(byte[] value) {
        if (value.length == 0 || value[value.length - 1] != tag) {
          throw new IllegalStateException("tag " + tag + " missing — inverse applied out of order");
        }
        byte[] out = new byte[value.length - 1];
        System.arraycopy(value, 0, out, 0, out.length);
        return out;
      }
    };
  }

  private static CodecPipeline pipeline(CodecCustomizer customizer) {
    return CodecPipeline.of(List.of(customizer));
  }

  @Nested
  class Surviving_a_configuration_change {

    @Test
    void bytes_written_before_a_transform_was_appended_still_decode_afterwards() {
      byte[] written = CodecPipeline.none().encode(PAYLOAD);

      CodecPipeline afterTheEdit = pipeline(chain -> chain.append("reverse", reversing()));

      assertThat(afterTheEdit.decode(written)).isEqualTo(PAYLOAD);
    }

    @Test
    void reversing_the_declaration_order_does_not_silently_corrupt() {
      byte[] written =
          pipeline(chain -> chain.append("a", tagging((byte) 1)).append("b", tagging((byte) 2)))
              .encode(PAYLOAD);

      CodecPipeline reordered =
          pipeline(chain -> chain.append("b", tagging((byte) 2)).append("a", tagging((byte) 1)));

      assertThat(reordered.decode(written)).isEqualTo(PAYLOAD);
    }

    @Test
    void removing_a_transform_that_existing_data_used_fails_loudly_rather_than_returning_garbage() {
      byte[] written = pipeline(chain -> chain.append("reverse", reversing())).encode(PAYLOAD);

      CodecPipeline withoutIt = CodecPipeline.none();

      assertThatThrownBy(() -> withoutIt.decode(written))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("reverse");
    }

    @Test
    void payloads_written_before_any_pipeline_existed_are_returned_untouched() {
      assertThat(CodecPipeline.none().decode(PAYLOAD)).isEqualTo(PAYLOAD);
    }
  }

  @Nested
  class As_a_codec_provider {

    /** A real factory rather than a fake, so the test exercises the composition, not a stand-in. */
    private final CodecFactory plain = new InMemorySubstrate().codecs();

    @Test
    void every_codec_the_factory_makes_already_carries_the_pipeline() {
      CodecPipeline pipeline = pipeline(chain -> chain.append("reverse", reversing()));

      Codec<String> pipelined = pipeline.factoryOver(plain).create(String.class);
      byte[] stored = pipelined.encode("the disk is full");

      assertThat(stored).isNotEqualTo(plain.create(String.class).encode("the disk is full"));
      assertThat(pipelined.decode(stored)).isEqualTo("the disk is full");
    }

    @Test
    void a_pipelined_codec_reads_what_an_earlier_chain_wrote() {
      byte[] written = CodecPipeline.none().factoryOver(plain).create(String.class).encode("hello");

      Codec<String> afterTheEdit =
          pipeline(chain -> chain.append("reverse", reversing()))
              .factoryOver(plain)
              .create(String.class);

      assertThat(afterTheEdit.decode(written)).isEqualTo("hello");
    }
  }

  @Nested
  class The_chain {

    @Test
    void applies_transforms_in_the_order_they_were_declared() {
      CodecPipeline pipeline =
          pipeline(chain -> chain.append("first", tagging((byte) 1)).append("second", reversing()));

      assertThat(pipeline.transforms()).containsExactly("first", "second");
      assertThat(pipeline.decode(pipeline.encode(PAYLOAD))).isEqualTo(PAYLOAD);
    }

    @Test
    void round_trips_an_empty_payload() {
      CodecPipeline pipeline = pipeline(chain -> chain.append("reverse", reversing()));

      assertThat(pipeline.decode(pipeline.encode(new byte[0]))).isEmpty();
    }

    @Test
    void refuses_the_same_transform_name_twice_because_the_name_identifies_it_in_stored_bytes() {
      assertThatThrownBy(
              () ->
                  CodecPipeline.of(
                      List.of(c -> c.append("dup", reversing()).append("dup", reversing()))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already in the chain");
    }

    @Test
    void refuses_a_blank_name() {
      assertThatThrownBy(() -> CodecPipeline.of(List.of(c -> c.append("  ", reversing()))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
    }
  }
}
