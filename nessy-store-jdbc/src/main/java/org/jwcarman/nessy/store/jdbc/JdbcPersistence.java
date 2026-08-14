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
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;

/**
 * The three doors a durable {@code AgentBuilder} actually needs, all over one Postgres database,
 * bootstrapped in one call: a {@link JdbcConversationStore}, a {@link JdbcParks} registry, and a
 * {@link JdbcTranscript}. {@link #create} exists because those three schemas are always stood up
 * together in practice — nothing here couples them beyond that convenience; each component still
 * works fine constructed on its own.
 */
public record JdbcPersistence(
    JdbcConversationStore store, JdbcParks parks, JdbcTranscript transcript) {

  public JdbcPersistence {
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(parks, "parks must not be null");
    Objects.requireNonNull(transcript, "transcript must not be null");
  }

  /** Bootstraps all three schemas against {@code dataSource}, then returns a working trio. */
  public static JdbcPersistence create(DataSource dataSource, ObjectMapper mapper) {
    return new JdbcPersistence(
        JdbcConversationStore.create(dataSource, mapper),
        JdbcParks.create(dataSource, mapper),
        JdbcTranscript.create(dataSource, mapper));
  }

  /** The durable {@link Memory}: verbatim retention over this pair's own {@link #transcript()}. */
  public Memory memory() {
    return new TranscriptMemory(transcript);
  }
}
