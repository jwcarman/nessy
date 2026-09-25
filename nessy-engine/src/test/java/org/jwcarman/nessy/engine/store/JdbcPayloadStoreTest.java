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

package org.jwcarman.nessy.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.spi.store.PayloadStore;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Content, kept where nothing else has to look at it. */
@Tag("container")
@DisplayName("Payloads in a database")
class JdbcPayloadStoreTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final PayloadStore unscoped =
      new JdbcPayloadStore(
          JdbcClient.create(database()), new JacksonCodecFactory(JsonMapper.builder().build()));

  private PayloadStore forSomeAgent() {
    return unscoped.forAgent(AgentId.random());
  }

  @Test
  @DisplayName("keeps content and hands it back whole")
  void round_trips() {
    PayloadStore payloads = forSomeAgent();
    List<Block> content =
        List.of(
            new Block.Text("first"),
            new Block.Provider("anthropic", "{\"sig\":\"x\"}"),
            new Block.Text("last"));

    PayloadRef ref = payloads.put(content);

    assertThat(payloads.get(ref)).isEqualTo(new PayloadStore.Resolved.Found(content));
  }

  /** The reason the reference is the content's hash rather than a number somebody handed out. */
  @Test
  @DisplayName("putting the same content twice is one reference and one row")
  void putting_twice_is_idempotent() {
    PayloadStore payloads = forSomeAgent();
    List<Block> content = List.of(new Block.Text("say it again"));

    PayloadRef first = payloads.put(content);
    PayloadRef again = payloads.put(content);

    assertThat(again).as("an effect retried leaves no second copy").isEqualTo(first);
  }

  @Test
  @DisplayName("different content is a different reference")
  void different_content_differs() {
    PayloadStore payloads = forSomeAgent();

    assertThat(payloads.put(List.of(new Block.Text("one"))))
        .isNotEqualTo(payloads.put(List.of(new Block.Text("two"))));
  }

  /** What the scoping buys: one agent cannot read another's, however the reference was obtained. */
  @Test
  @DisplayName("an agent cannot resolve another agent's content")
  void agents_do_not_share() {
    PayloadStore mine = forSomeAgent();
    PayloadStore theirs = forSomeAgent();
    List<Block> content = List.of(new Block.Text("the same words"));

    PayloadRef ref = mine.put(content);

    assertThat(mine.get(ref)).isInstanceOf(PayloadStore.Resolved.Found.class);
    assertThat(theirs.get(ref))
        .as("identical content, stored twice, and not shared")
        .isEqualTo(new PayloadStore.Resolved.Missing());
  }

  @Test
  @DisplayName("a window of references is one query, and every one gets an answer")
  void resolves_a_batch() {
    PayloadStore payloads = forSomeAgent();
    PayloadRef one = payloads.put(List.of(new Block.Text("one")));
    PayloadRef two = payloads.put(List.of(new Block.Text("two")));
    PayloadRef never = new PayloadRef("00".repeat(32));

    Map<PayloadRef, PayloadStore.Resolved> found = payloads.get(List.of(one, two, never));

    assertThat(found).hasSize(3);
    assertThat(found.get(one))
        .isEqualTo(new PayloadStore.Resolved.Found(List.of(new Block.Text("one"))));
    assertThat(found.get(two))
        .isEqualTo(new PayloadStore.Resolved.Found(List.of(new Block.Text("two"))));
    assertThat(found.get(never))
        .as("asked about and not there, rather than absent from the answer")
        .isEqualTo(new PayloadStore.Resolved.Missing());
  }

  @Test
  @DisplayName("refuses to be used before it knows whose content it is keeping")
  void unscoped_is_refused() {
    List<Block> content = List.of(new Block.Text("whose is this?"));

    assertThatThrownBy(() -> unscoped.put(content))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not scoped");
  }
}
