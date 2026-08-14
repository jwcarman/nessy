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
package org.jwcarman.nessy.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The pair a durable {@code AgentBuilder} actually needs: a {@link JdbcConversationStore} and a
 * {@link JdbcMemory}, both bootstrapped against the same Postgres database in one call. {@link
 * #create} exists because those two schemas are always stood up together in practice — nothing here
 * couples them beyond that convenience; either component still works fine constructed on its own.
 */
public record JdbcPersistence(JdbcConversationStore store, JdbcMemory memory) {

  public JdbcPersistence {
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(memory, "memory must not be null");
  }

  /** Bootstraps both schemas against {@code dataSource}, then returns a working pair. */
  public static JdbcPersistence create(DataSource dataSource, ObjectMapper mapper) {
    return new JdbcPersistence(
        JdbcConversationStore.create(dataSource, mapper), JdbcMemory.create(dataSource, mapper));
  }
}
