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
package org.jwcarman.nessy.substrate.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A module-local check (not part of {@code SubstrateContract} — {@code InMemorySubstrate} has no
 * SQL layer to lose precision through) that {@link JdbcSubstrate} really does stamp {@code
 * updated_at}/{@code appended_at} from its injected {@link Clock}, rounded to microsecond
 * resolution by {@code TIMESTAMPTZ}, rather than SQL {@code now()}. Without this, an implementation
 * that wrote {@code now()} directly and dropped the {@code Clock} field entirely would still pass
 * every {@code SubstrateContract} test, because the battery never asserts on a stamped timestamp's
 * value.
 */
@Testcontainers
class JdbcSubstrateClockTest {

  private static final String KIND = "clock";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

  private static DataSource dataSource;

  @BeforeAll
  static void applySchemaOnce() {
    dataSource = dataSource();
    applyShippedSchema(dataSource);
  }

  @Test
  void a_document_write_is_stamped_from_the_injected_clock_rounded_to_microseconds() {
    Instant nanosecondPrecise = Instant.parse("2026-01-01T00:00:00.123456789Z");
    Clock clock = Clock.fixed(nanosecondPrecise, ZoneOffset.UTC);
    JdbcSubstrate substrate = new JdbcSubstrate(dataSource, clock);

    substrate.write(KIND, "doc", "payload".getBytes(UTF_8), 0);

    assertThat(substrate.read(KIND, "doc"))
        .hasValueSatisfying(
            document ->
                assertThat(document.updatedAt()).isEqualTo(roundToMicros(nanosecondPrecise)));
  }

  @Test
  void a_journal_append_is_stamped_from_the_injected_clock_rounded_to_microseconds() {
    Instant nanosecondPrecise = Instant.parse("2026-06-15T12:30:45.987654321Z");
    Clock clock = Clock.fixed(nanosecondPrecise, ZoneOffset.UTC);
    JdbcSubstrate substrate = new JdbcSubstrate(dataSource, clock);

    substrate.append(KIND, "entry", 1, "payload".getBytes(UTF_8));

    assertThat(substrate.entries(KIND, "entry", 1))
        .singleElement()
        .satisfies(
            entry -> assertThat(entry.appendedAt()).isEqualTo(roundToMicros(nanosecondPrecise)));
  }

  /**
   * {@code TIMESTAMPTZ} is microsecond-resolution and rounds a finer-grained input to the nearest
   * microsecond (not a floor truncation) — {@code .123456789} lands on {@code .123457}, half a
   * microsecond and above rounding up. This mirrors what the database and driver actually do, so
   * the assertions above hold regardless of which nanosecond fraction a test picks.
   */
  private static Instant roundToMicros(Instant instant) {
    long roundedMicros = Math.round(instant.getNano() / 1000.0);
    long carrySeconds = roundedMicros / 1_000_000;
    long remainderMicros = roundedMicros % 1_000_000;
    return Instant.ofEpochSecond(instant.getEpochSecond() + carrySeconds, remainderMicros * 1000);
  }

  private static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private static void applyShippedSchema(DataSource dataSource) {
    String ddl = readShippedSchema();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(ddl);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to apply shipped schema", e);
    }
  }

  private static String readShippedSchema() {
    try (InputStream in = JdbcSubstrate.class.getResourceAsStream("nessy-postgresql.sql")) {
      if (in == null) {
        throw new IllegalStateException("nessy-postgresql.sql not found on the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
