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

package org.jwcarman.nessy.backend.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Tag("container")
@DisplayName("A JDBC backend given a storage transform")
class JdbcBackendStorageTransformTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final JacksonCodecFactory VALUES =
      new JacksonCodecFactory(JsonMapper.builder().build());
  private static final List<Block> BLOCKS = List.of(new Block.Text("say it again"));

  private final DataSource database = database();
  private final JdbcClient jdbc = JdbcClient.create(database);

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private void assertOneReferenceOneRowTransformedAndReadsBack(Payloads unscoped) {
    AgentId agent = AgentId.random();
    Payloads payloads = unscoped.forAgent(agent);

    PayloadRef first = payloads.put(BLOCKS);
    PayloadRef again = payloads.put(BLOCKS);

    List<byte[]> stored =
        jdbc.sql("SELECT content FROM nessy_payload WHERE agent_id = ?")
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
    assertOneReferenceOneRowTransformedAndReadsBack(
        new JdbcDirectBackend(
                database, new JdbcTransactionManager(database), VALUES, new NeverTheSameBytes())
            .payloads());
  }

  @Test
  @DisplayName("the queued door keeps the same content once, transformed")
  void the_queued_door_keeps_the_same_content_once_transformed() {
    assertOneReferenceOneRowTransformedAndReadsBack(
        new JdbcQueuedBackend(
                database, new JdbcTransactionManager(database), VALUES, new NeverTheSameBytes())
            .payloads());
  }
}
