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
package org.jwcarman.nessy.transcript.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.spi.memory.Transcript.Entry;
import org.jwcarman.nessy.spi.memory.TranscriptContract;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The TCK run against a real Cassandra, plus the Cassandra-specific pins the in-memory transcript
 * has no opinion on: bootstrap idempotency, and the LWT loop's behavior under real contention.
 * Requires Docker; tagged {@code container} so the offline default build never needs it.
 */
@Testcontainers
@Tag("container")
class CassandraTranscriptTest extends TranscriptContract {

  private static final String KEYSPACE = "nessy_transcript_test";
  private static final int RACING_APPENDER_COUNT = 16;

  @Container
  static final CassandraContainer<?> CASSANDRA = new CassandraContainer<>("cassandra:5.0");

  private static CqlSession session;

  private Transcript transcript;

  @BeforeAll
  static void nessy_store_cassandra_test_points_a_session_at_the_container() {
    InetSocketAddress contactPoint = CASSANDRA.getContactPoint();
    String localDatacenter = CASSANDRA.getLocalDatacenter();
    try (CqlSession bootstrapSession =
        CqlSession.builder()
            .addContactPoint(contactPoint)
            .withLocalDatacenter(localDatacenter)
            .build()) {
      bootstrapSession.execute(
          "CREATE KEYSPACE IF NOT EXISTS "
              + KEYSPACE
              + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
    }
    session =
        CqlSession.builder()
            .addContactPoint(contactPoint)
            .withLocalDatacenter(localDatacenter)
            .withKeyspace(KEYSPACE)
            .build();
  }

  @BeforeEach
  void a_fresh_transcript_over_an_empty_table() {
    transcript = CassandraTranscript.create(session, new ObjectMapper());
    session.execute("TRUNCATE " + KEYSPACE + ".nessy_transcript");
  }

  @Override
  protected Transcript transcript() {
    return transcript;
  }

  @Test
  void the_schema_bootstrap_is_idempotent() {
    assertThatCode(
            () -> {
              CassandraTranscript.create(session, new ObjectMapper());
              CassandraTranscript.create(session, new ObjectMapper());
            })
        .doesNotThrowAnyException();
  }

  @Test
  void racing_appenders_on_one_conversation_mint_a_gap_free_version_for_every_distinct_message() {
    ConversationId id = ConversationId.generate();

    RacingAppenders<Entry> racers =
        new RacingAppenders<>(
            RACING_APPENDER_COUNT,
            index -> transcript.append(id, Message.user("telling-" + index)));
    List<Entry> results = racers.runToCompletion();

    assertThat(results).hasSize(RACING_APPENDER_COUNT);
    assertThat(results).extracting(Entry::version).doesNotHaveDuplicates();
    assertThat(results)
        .extracting(Entry::version)
        .containsExactlyInAnyOrderElementsOf(
            LongStream.range(0, RACING_APPENDER_COUNT).boxed().toList());
    List<Entry> stored = transcript.all(id);
    assertThat(stored).hasSize(RACING_APPENDER_COUNT);
    assertThat(stored).extracting(Entry::version).isSorted();
    assertThat(stored)
        .extracting(entry -> entry.message().content().getFirst())
        .hasSize(RACING_APPENDER_COUNT)
        .doesNotHaveDuplicates();
  }

  @Test
  void two_racing_identical_tellings_hold_the_no_stutter_rule() {
    ConversationId id = ConversationId.generate();
    Message message = Message.user("racing telling");

    RacingAppenders<Entry> racers =
        new RacingAppenders<>(2, index -> transcript.append(id, message));
    List<Entry> results = racers.runToCompletion();

    assertThat(results).extracting(Entry::version).containsOnly(results.getFirst().version());
    assertThat(transcript.all(id)).extracting(Entry::message).containsExactly(message);
  }
}
