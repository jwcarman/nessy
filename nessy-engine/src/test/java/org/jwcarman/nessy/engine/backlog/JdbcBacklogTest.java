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

package org.jwcarman.nessy.engine.backlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Backlog;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** What is waiting for an agent, and what a strategy can do about it without reading it. */
@Tag("container")
@DisplayName("A backlog in a database")
class JdbcBacklogTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType TYPE = new AgentType("chat");

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final JdbcClient jdbc = JdbcClient.create(database());
  private final Codec<String> codec =
      new JacksonCodecFactory(JsonMapper.builder().build()).create(String.class);
  private final Backlog<String> backlog = new JdbcBacklog<>(jdbc, codec, TYPE, AgentId.random());

  private static BacklogItem<String> said(String what) {
    return new BacklogItem<>(what, Instant.parse("2026-09-25T12:00:00Z"));
  }

  private List<String> waiting() {
    return backlog.all().stream().map(BacklogItem::observation).toList();
  }

  @Test
  @DisplayName("starts with nothing waiting")
  void starts_empty() {
    assertThat(backlog.size()).isZero();
    assertThat(backlog.all()).isEmpty();
  }

  /** Keep everything: the right answer for anything a person said. */
  @Test
  @DisplayName("appending keeps them all, in the order they arrived")
  void append_keeps_order() {
    backlog.append(said("one"));
    backlog.append(said("two"));
    backlog.append(said("three"));

    assertThat(waiting()).containsExactly("one", "two", "three");
    assertThat(backlog.size()).isEqualTo(3);
  }

  /** Keep only the latest: the right answer for a clock tick or a resync. */
  @Test
  @DisplayName("replacing leaves exactly one, however many were waiting")
  void replace_all_leaves_one() {
    backlog.append(said("stale"));
    backlog.append(said("staler"));

    backlog.replaceAll(said("current"));

    assertThat(waiting()).containsExactly("current");
  }

  /** A bounded buffer: a count, a delete and an insert, with nothing decoded. */
  @Test
  @DisplayName("a bound is held by counting and dropping the oldest")
  void a_bounded_buffer_holds_its_bound() {
    int max = 5;
    for (int i = 1; i <= 8; i++) {
      if (backlog.size() >= max) {
        backlog.dropOldest(backlog.size() - max + 1);
      }
      backlog.append(said("item " + i));
    }

    assertThat(backlog.size()).isEqualTo(max);
    assertThat(waiting())
        .as("what falls out of a full queue is what waited longest")
        .containsExactly("item 4", "item 5", "item 6", "item 7", "item 8");
  }

  @Test
  @DisplayName("dropping none, or fewer than none, is not a change")
  void dropping_nothing_does_nothing() {
    backlog.append(said("kept"));

    backlog.dropOldest(0);
    backlog.dropOldest(-1);

    assertThat(waiting()).containsExactly("kept");
  }

  @Test
  @DisplayName("rewriting says what the backlog should be instead")
  void rewrite_replaces_everything() {
    backlog.append(said("a"));
    backlog.append(said("b"));
    backlog.append(said("c"));

    backlog.rewrite(List.of(said("c"), said("a")));

    assertThat(waiting()).containsExactly("c", "a");
  }

  @Test
  @DisplayName("rewriting to nothing drops everything")
  void rewrite_can_empty_it() {
    backlog.append(said("doomed"));

    backlog.rewrite(List.of());

    assertThat(backlog.size()).isZero();
  }

  /** The time is the arriving item's own, so a strategy that reasons about age reads no clock. */
  @Test
  @DisplayName("keeps when each item said it arrived")
  void arrival_times_survive() {
    Instant early = Instant.parse("2026-09-25T09:00:00Z");
    backlog.append(new BacklogItem<>("first", early));

    assertThat(backlog.all().getFirst().arrivedAt()).isEqualTo(early);
  }

  @Test
  @DisplayName("agents do not see each other's backlogs")
  void agents_are_separate() {
    Backlog<String> theirs = new JdbcBacklog<>(jdbc, codec, TYPE, AgentId.random());
    backlog.append(said("mine"));

    assertThat(theirs.all()).isEmpty();
    assertThat(waiting()).containsExactly("mine");
  }

  @Test
  @DisplayName("forgetting an agent takes its backlog with it")
  void forgetting_clears_it() {
    AgentId doomed = AgentId.random();
    Backlog<String> its = new JdbcBacklog<>(jdbc, codec, TYPE, doomed);
    its.append(said("remember me"));

    JdbcBacklog.forget(jdbc, TYPE, doomed);

    assertThat(its.all()).isEmpty();
  }
}
