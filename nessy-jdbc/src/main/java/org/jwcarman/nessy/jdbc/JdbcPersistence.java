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
package org.jwcarman.nessy.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * The three doors a durable {@link org.jwcarman.nessy.AgentConfig} actually needs, plus the summary
 * shelf a summarizing {@code ContextHydrator} reaches for, the plan facility's own store, the
 * notebook facility's own store, the subagent facility's own link registry, and the intent
 * facility's own store, all over one database — any of the five {@link JdbcDialect} knows (design
 * §2) — bootstrapped in one call: a {@link JdbcConversationStore}, a {@link JdbcParks} registry, a
 * {@link JdbcTranscript}, a {@link JdbcSummaryStore}, a {@link JdbcPlanStore}, a {@link
 * JdbcNotebook}, a {@link JdbcSubagentLinks}, and a {@link JdbcIntentStore}. {@link #create} exists
 * because those eight schemas are always stood up together in practice — nothing here couples them
 * beyond that convenience; each component still works fine constructed on its own.
 *
 * <p>{@link #create(DataSource, ObjectMapper)} is this module's one shared dialect-resolution seam
 * (design §2): it resolves the dialect exactly once, from one borrowed connection, before any of
 * the eight components bootstraps its own schema, then hands that single resolved value down to
 * each component's own explicit-dialect {@code create} overload — eight schemas, one resolution,
 * not eight independent ones that could in principle disagree. {@link #create(DataSource,
 * ObjectMapper, JdbcDialect)} carries the same null-means-resolve contract every one of those eight
 * components' own explicit-dialect overload already has: passing {@code null} does not skip
 * resolution, it asks for exactly the one-resolution behavior {@link #create(DataSource,
 * ObjectMapper)} provides; passing a non-null value bypasses resolution entirely, for every
 * component at once.
 */
public record JdbcPersistence(
    JdbcConversationStore store,
    JdbcParks parks,
    JdbcTranscript transcript,
    JdbcSummaryStore summaries,
    JdbcPlanStore planStore,
    JdbcNotebook notebook,
    JdbcSubagentLinks subagentLinks,
    JdbcIntentStore intentStore) {

  public JdbcPersistence {
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(parks, "parks must not be null");
    Objects.requireNonNull(transcript, "transcript must not be null");
    Objects.requireNonNull(summaries, "summaries must not be null");
    Objects.requireNonNull(planStore, "planStore must not be null");
    Objects.requireNonNull(notebook, "notebook must not be null");
    Objects.requireNonNull(subagentLinks, "subagentLinks must not be null");
    Objects.requireNonNull(intentStore, "intentStore must not be null");
  }

  /** Bootstraps all eight schemas against {@code dataSource}, resolving the dialect once. */
  public static JdbcPersistence create(DataSource dataSource, ObjectMapper mapper) {
    return create(dataSource, mapper, null);
  }

  /**
   * Bootstraps all eight schemas against {@code dataSource}. {@code dialect} of {@code null} means
   * resolve — exactly once, here, before any component bootstraps — the same contract every
   * component's own explicit-dialect overload already has; a non-null {@code dialect} bypasses
   * resolution entirely and is handed to all eight components unchanged.
   */
  public static JdbcPersistence create(
      DataSource dataSource, ObjectMapper mapper, JdbcDialect dialect) {
    JdbcDialect resolved = dialect != null ? dialect : resolve(dataSource);
    return new JdbcPersistence(
        JdbcConversationStore.create(dataSource, mapper, resolved),
        JdbcParks.create(dataSource, mapper, resolved),
        JdbcTranscript.create(dataSource, mapper, resolved),
        JdbcSummaryStore.create(dataSource, resolved),
        JdbcPlanStore.create(dataSource, resolved),
        JdbcNotebook.create(dataSource, resolved),
        JdbcSubagentLinks.create(dataSource, resolved),
        JdbcIntentStore.create(dataSource, resolved));
  }

  /** The one connection this whole bootstrap borrows purely to resolve the dialect once. */
  private static JdbcDialect resolve(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      return JdbcDialect.resolve(connection.getMetaData());
    } catch (SQLException e) {
      throw new IllegalStateException("failed to resolve the JDBC dialect for bootstrap", e);
    }
  }

  /** The durable {@link Memory}: verbatim retention over this pair's own {@link #transcript()}. */
  public Memory memory() {
    return Memory.pipeline(transcript);
  }
}
