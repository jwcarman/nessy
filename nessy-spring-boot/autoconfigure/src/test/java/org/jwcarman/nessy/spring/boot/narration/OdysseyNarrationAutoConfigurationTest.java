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
package org.jwcarman.nessy.spring.boot.narration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.store.StorageCodec;
import org.jwcarman.nessy.narration.odyssey.AgentStreams;
import org.jwcarman.nessy.narration.odyssey.OdysseyNarrator;
import org.jwcarman.odyssey.autoconfigure.OdysseyAutoConfiguration;
import org.jwcarman.odyssey.core.Odyssey;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Wiring the narrator")
class OdysseyNarrationAutoConfigurationTest {

  /** What an application brings: a mapper. Substrate's in-memory backend needs nothing else. */
  @Configuration(proxyBeanMethods = false)
  static class AnApplication {
    @Bean
    ObjectMapper mapper() {
      return JsonMapper.builder().build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnApplicationWithItsOwnNarrator {
    @Bean
    AgentEventListener mine() {
      return AgentEventListener.none();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  SubstrateCodecAutoConfiguration.class,
                  SubstrateAutoConfiguration.class,
                  OdysseyAutoConfiguration.class,
                  OdysseyNarrationAutoConfiguration.class))
          .withUserConfiguration(AnApplication.class);

  @Test
  @DisplayName("an Odyssey on the classpath and a mapper in the context is the whole setup")
  void with_odyssey_present_the_narrator_is_odysseys() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(Odyssey.class);
          assertThat(context).hasSingleBean(AgentStreams.class);
          assertThat(context).hasSingleBean(OdysseyNarrator.class);
        });
  }

  @Test
  @DisplayName("and what it narrates is journaled, in memory here")
  void narrating_writes_to_the_journal() {
    runner.run(
        context -> {
          AgentEventListener narrator = context.getBean(OdysseyNarrator.class);
          AgentId agentId = new AgentId(UUID.randomUUID());
          // No exception is the assertion: the in-memory journal accepted the entry. What it
          // holds is read back over SSE, which the web example exercises end to end.
          narrator.on(new AgentType("chat"), agentId, new AgentEvent.ContentDelta("hi"));
          assertThat(
                  context.getBean(AgentStreams.class).stream(new AgentType("chat"), agentId)
                      .publish("terminated", new AgentEvent.Terminated()))
              .isNotBlank();
        });
  }

  @Test
  @DisplayName("a listener the application declared is heard beside it")
  void an_applications_own_listener_is_kept_too() {
    runner
        .withUserConfiguration(AnApplicationWithItsOwnNarrator.class)
        .run(
            context -> {
              assertThat(context.getBeansOfType(AgentEventListener.class)).hasSize(2);
              assertThat(context).hasSingleBean(OdysseyNarrator.class);
            });
  }

  @Test
  @DisplayName("without an Odyssey there is nothing here, and the engine's silent default stands")
  void without_odyssey_nothing_is_declared() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OdysseyNarrationAutoConfiguration.class))
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(AgentStreams.class);
              assertThat(context).doesNotHaveBean(OdysseyNarrator.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class AnApplicationThatEncrypts {
    @Bean
    StorageCodec storage() {
      return StorageCodec.of(
          new Codec<>() {
            @Override
            public byte[] encode(byte[] bytes) {
              byte[] out = bytes.clone();
              for (int i = 0; i < out.length; i++) {
                out[i] ^= 0x5A;
              }
              return out;
            }

            @Override
            public byte[] decode(byte[] bytes) {
              return encode(bytes);
            }
          });
    }
  }

  @Test
  @DisplayName("the engine's storage codec is the journal's payload transformer too")
  void a_storage_codec_reaches_the_journal() {
    runner
        .withUserConfiguration(AnApplicationThatEncrypts.class)
        .run(
            context -> {
              PayloadTransformer transformer = context.getBean(PayloadTransformer.class);
              byte[] plain = "hello".getBytes(StandardCharsets.UTF_8);
              assertThat(transformer.encode(plain)).isNotEqualTo(plain);
              assertThat(transformer.decode(transformer.encode(plain))).isEqualTo(plain);
            });
  }

  @Test
  @DisplayName("without one, Substrate's own identity transformer stands")
  void without_a_storage_codec_the_journal_is_left_alone() {
    runner.run(
        context -> {
          PayloadTransformer transformer = context.getBean(PayloadTransformer.class);
          byte[] plain = "hello".getBytes(StandardCharsets.UTF_8);
          assertThat(transformer.encode(plain)).isEqualTo(plain);
        });
  }

  @Test
  void the_ttl_is_configurable() {
    runner
        .withPropertyValues(
            "nessy.narration.odyssey.inactivity-ttl=2h", "nessy.narration.odyssey.entry-ttl=30m")
        .run(
            context -> {
              OdysseyNarrationProperties properties =
                  context.getBean(OdysseyNarrationProperties.class);
              assertThat(properties.ttl().inactivityTtl()).hasHours(2);
              assertThat(properties.ttl().entryTtl()).hasMinutes(30);
              assertThat(properties.ttl().retentionTtl()).hasHours(1);
            });
  }
}
