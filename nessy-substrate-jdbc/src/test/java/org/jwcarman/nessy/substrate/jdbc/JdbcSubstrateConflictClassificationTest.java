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

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves {@link JdbcSubstrate}'s package-private {@code isConflict(SQLException)} classifies every
 * SQLSTATE that means "the caller lost a race" as a conflict — including {@code 40P01} (deadlock
 * detected) and {@code 40001} (serialization failure), not just {@code 23505} (unique violation) —
 * and does not over-broadly classify an unrelated failure (a connectivity error) as one. A real
 * deadlock between two concurrent {@code batch} calls is not deterministically reproducible, so
 * this battery proves the mapping directly by constructing {@link SQLException}s with each SQLSTATE
 * rather than racing threads against a container.
 */
class JdbcSubstrateConflictClassificationTest {

  @ParameterizedTest
  @ValueSource(strings = {"23505", "40P01", "40001"})
  void a_conflict_sqlstate_is_classified_as_a_conflict(String sqlState) {
    SQLException exception = new SQLException("simulated", sqlState);

    assertThat(JdbcSubstrate.isConflict(exception)).isTrue();
  }

  @Test
  void a_connection_failure_sqlstate_is_not_classified_as_a_conflict() {
    SQLException exception = new SQLException("simulated", "08006");

    assertThat(JdbcSubstrate.isConflict(exception)).isFalse();
  }
}
