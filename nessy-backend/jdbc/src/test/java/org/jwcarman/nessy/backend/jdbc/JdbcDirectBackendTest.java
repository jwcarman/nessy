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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link JdbcDirectBackend} hands back is not a copy of anything -- it is the same three
 * tables every instance built over this database sees, which is what "shared" means.
 */
@Tag("container")
@DisplayName("A direct backend over a database")
class JdbcDirectBackendTest {

  /**
   * What the Spring auto-configuration hands in, spelled out here. JdbcDirectBackend takes a codec
   * factory rather than making one, so a test says which bytes it means exactly as an application
   * does.
   */
  private static DirectBackend backend(DataSource dataSource) {
    return new JdbcDirectBackend(
        dataSource,
        new JdbcTransactionManager(dataSource),
        new JacksonCodecFactory(JsonMapper.builder().build()));
  }

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType TYPE = new AgentType("chat");
  private static final LockKind KIND = new LockKind("turn");

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final DataSource dataSource = database();

  @Test
  @DisplayName(
      "a payload put through one instance is readable through another built over the same database")
  void payloads_are_shared_across_instances() {
    DirectBackend writer = backend(dataSource);
    DirectBackend reader = backend(dataSource);
    AgentId agent = AgentId.random();

    PayloadRef ref = writer.payloads().forAgent(agent).put(List.of(new Block.Text("hello")));

    assertThat(reader.payloads().forAgent(agent).get(ref))
        .isInstanceOf(Payloads.Resolved.Found.class)
        .isEqualTo(new Payloads.Resolved.Found(List.of(new Block.Text("hello"))));
  }

  @Test
  @DisplayName(
      "an event appended through one instance is readable through another built over the same database")
  void events_are_shared_across_instances() {
    DirectBackend writer = backend(dataSource);
    DirectBackend reader = backend(dataSource);
    AgentId agent = AgentId.random();
    AgentEvent event = new AgentEvent.TurnStarted(new Seq(1), new TurnId(1), new PayloadRef("p1"));

    writer.events().append(TYPE, agent, List.of(event), Seq.NONE);

    assertThat(reader.events().readAll(TYPE, agent)).containsExactly(event);
  }

  @Test
  @DisplayName("a lock held through one instance excludes a second instance over the same agent")
  void locks_exclude_across_instances() throws Exception {
    DirectBackend first = backend(dataSource);
    DirectBackend second = backend(dataSource);
    AgentId agent = AgentId.random();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(
            () ->
                first
                    .locks()
                    .withLock(
                        KIND,
                        TYPE,
                        agent,
                        () -> {
                          holding.countDown();
                          await(release);
                          return null;
                        }));

    assertThat(holding.await(5, TimeUnit.SECONDS)).as("first instance took the lock").isTrue();
    CompletableFuture<String> waiter =
        CompletableFuture.supplyAsync(
            () -> second.locks().withLock(KIND, TYPE, agent, () -> "free"));
    assertThat(waiter.isDone())
        .as("a second instance, over the same database, waits while the first holds it")
        .isFalse();
    Thread.sleep(200);
    assertThat(waiter.isDone()).as("still waiting behind the first instance").isFalse();

    release.countDown();
    holder.join();

    assertThat(waiter.get(5, TimeUnit.SECONDS))
        .as("released once the first instance's work finishes")
        .isEqualTo("free");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }
}
