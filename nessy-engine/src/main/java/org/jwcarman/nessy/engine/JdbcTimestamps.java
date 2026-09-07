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
package org.jwcarman.nessy.engine;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The ONE place a {@link Instant} becomes a JDBC parameter.
 *
 * <p>Bound raw, a bare {@link Instant} works against H2, which silently tolerates it -- and fails
 * against real PostgreSQL, whose driver cannot infer a SQL type for {@code java.time.Instant} and
 * throws {@code Can't infer the SQL type to use for an instance of java.time.Instant}. Measured by
 * {@code EffectStorePostgresCertificationTest} and {@code AgentStorePostgresCertificationTest}:
 * every store that binds a moment in time does it through {@link #ts(Instant)}, so there is exactly
 * ONE way this conversion happens rather than one correct way and one silent trap.
 */
final class JdbcTimestamps {

  private JdbcTimestamps() {}

  static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
