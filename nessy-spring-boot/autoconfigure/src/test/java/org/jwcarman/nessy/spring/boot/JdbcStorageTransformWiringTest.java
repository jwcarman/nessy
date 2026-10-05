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
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.nessy.api.AgentId;
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
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The JDBC backends the starter builds, over a real database, given a storage transform that never
 * writes the same bytes twice: the same content is one reference and one row, and what is stored is
 * still transformed.
 */
@Tag("container")
@DisplayName("The storage transform in the JDBC backends")
class JdbcStorageTransformWiringTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final List<Block> BLOCKS = List.of(new Block.Text("say it again"));

  @Configuration(proxyBeanMethods = false)
  static class AnInferenceProvider {

    @Bean
    InferenceProvider inference() {
      return (request, narrator) ->
          new InferenceResult.Refusal("this provider is never actually called");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ADatabaseAndANonDeterministicTransform {

    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** A counter byte, from 1 to 100, in front: never the same bytes twice, never a brace. */
    @Bean
    StorageCodecConfigurer storage() {
      AtomicInteger counter = new AtomicInteger();
      return original ->
          original.andThen(
              new Codec<>() {
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
              });
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class,
                  NessyAutoConfiguration.class,
                  JdbcBackendAutoConfiguration.class))
          .withUserConfiguration(
              AnInferenceProvider.class, ADatabaseAndANonDeterministicTransform.class)
          .withPropertyValues(
              "nessy.model=a-test-model",
              "nessy.provider=inference",
              "nessy.system-prompt=you are a test assistant");

  private static void assertOneReferenceOneRowStillTransformed(
      Payloads unscoped, DataSource dataSource) {
    AgentId agent = AgentId.random();
    Payloads payloads = unscoped.forAgent(agent);

    PayloadRef first = payloads.put(BLOCKS);
    PayloadRef again = payloads.put(BLOCKS);

    List<byte[]> stored =
        JdbcClient.create(dataSource)
            .sql("SELECT content FROM nessy_payload WHERE agent_id = ?")
            .params(agent.value())
            .query(byte[].class)
            .list();
    assertThat(again).isEqualTo(first);
    assertThat(stored).hasSize(1);
    assertThat(stored.getFirst()[0]).as("not plain JSON").isNotEqualTo((byte) '{');
    assertThat(payloads.get(first)).isEqualTo(new Payloads.Resolved.Found(BLOCKS));
  }

  @Test
  @DisplayName("the direct door keeps the same content once, transformed")
  void the_direct_door_keeps_the_same_content_once_transformed() {
    runner.run(
        context ->
            assertOneReferenceOneRowStillTransformed(
                context.getBean(DirectBackend.class).payloads(),
                context.getBean(DataSource.class)));
  }

  @Test
  @DisplayName("the queued door keeps the same content once, transformed")
  void the_queued_door_keeps_the_same_content_once_transformed() {
    runner.run(
        context ->
            assertOneReferenceOneRowStillTransformed(
                context.getBean(QueuedBackend.class).payloads(),
                context.getBean(DataSource.class)));
  }
}
