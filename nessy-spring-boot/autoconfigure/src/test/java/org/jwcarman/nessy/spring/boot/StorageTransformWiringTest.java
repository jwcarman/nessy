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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.StorageCodecConfigurer;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the backends do with the storage transform an application declares: the same content is one
 * reference, and an application's own {@code CodecFactory} is still the one the backend uses.
 */
@DisplayName("The storage transform in the backends")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StorageTransformWiringTest {

  private static final List<Block> BLOCKS = List.of(new Block.Text("say it again"));

  /** A transform that never writes the same bytes twice: a counter byte in front. */
  private static Codec<byte[]> neverTheSameBytes() {
    AtomicInteger counter = new AtomicInteger();
    return new Codec<>() {
      @Override
      public byte[] encode(byte[] bytes) {
        byte[] out = new byte[bytes.length + 1];
        out[0] = (byte) (counter.incrementAndGet() % 100 + 1);
        System.arraycopy(bytes, 0, out, 1, bytes.length);
        return out;
      }

      @Override
      public byte[] decode(byte[] bytes) {
        return Arrays.copyOfRange(bytes, 1, bytes.length);
      }
    };
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  NessyAutoConfiguration.class,
                  InMemoryBackendAutoConfiguration.class))
          .withUserConfiguration(AnInferenceProvider.class)
          .withPropertyValues(
              "nessy.model=a-test-model",
              "nessy.provider=inference",
              "nessy.system-prompt=you are a test assistant");

  @Configuration(proxyBeanMethods = false)
  static class AnInferenceProvider {

    @Bean
    InferenceProvider inference() {
      return (request, narrator) ->
          new InferenceResult.Refusal("this provider is never actually called");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ANonDeterministicTransform {

    @Bean
    StorageCodecConfigurer storage() {
      return original -> original.andThen(neverTheSameBytes());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnApplicationsOwnCodecFactory {

    @Bean
    CodecFactory codecs() {
      JacksonCodecFactory jackson = new JacksonCodecFactory(JsonMapper.builder().build());
      Codec<byte[]> transform = neverTheSameBytes();
      return new CodecFactory() {
        @Override
        public <T> Codec<T> create(TypeRef<T> type) {
          return jackson.create(type).andThen(transform);
        }
      };
    }
  }

  private static void assertOneReference(Payloads payloads) {
    PayloadRef first = payloads.put(BLOCKS);
    PayloadRef again = payloads.put(BLOCKS);

    assertThat(again).isEqualTo(first);
    assertThat(payloads.get(first)).isEqualTo(new Payloads.Resolved.Found(BLOCKS));
  }

  @Test
  void
      with_a_transform_that_never_writes_the_same_bytes_twice_the_direct_door_keeps_content_once() {
    runner
        .withUserConfiguration(ANonDeterministicTransform.class)
        .run(context -> assertOneReference(context.getBean(DirectBackend.class).payloads()));
  }

  @Test
  void
      with_a_transform_that_never_writes_the_same_bytes_twice_the_queued_door_keeps_content_once() {
    runner
        .withUserConfiguration(ANonDeterministicTransform.class)
        .run(context -> assertOneReference(context.getBean(QueuedBackend.class).payloads()));
  }

  @Test
  void a_codec_factory_the_application_declares_is_still_the_one_the_backends_use() {
    runner
        .withUserConfiguration(AnApplicationsOwnCodecFactory.class)
        .run(
            context -> {
              Payloads direct = context.getBean(DirectBackend.class).payloads();
              Payloads queued = context.getBean(QueuedBackend.class).payloads();

              assertThat(context.getBean(CodecFactory.class))
                  .isSameAs(context.getBean("codecs", CodecFactory.class));
              assertThat(direct.put(BLOCKS))
                  .as("that factory already includes the transform, so it is hashed after it")
                  .isNotEqualTo(direct.put(BLOCKS));
              assertThat(queued.put(BLOCKS)).isNotEqualTo(queued.put(BLOCKS));
              PayloadRef ref = direct.put(BLOCKS);
              assertThat(direct.get(ref)).isEqualTo(new Payloads.Resolved.Found(BLOCKS));
            });
  }
}
