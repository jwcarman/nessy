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
package org.jwcarman.nessy.approval.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.codec.jackson2.Jackson2CodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What an agent has declared it is trying to do, in a table of its own.
 *
 * <p>One row per agent: a declaration REPLACES the previous one rather than accumulating, because
 * what matters is the intent an agent is acting under now. "Per agent" means the TYPE and the id
 * together — an id is unique within its type and no further.
 *
 * <p><b>Concurrent declarations settle rather than clobber.</b> The write is conditional on the
 * version that was read, and a loser retries — so two callers declaring at the same moment produce
 * two declarations one after the other, never one silently overwriting the other.
 */
public final class JdbcIntentStore<T> implements IntentStore<T> {

  private static final String WHERE_AGENT = " WHERE agent_type = ? AND agent_id = ?";
  private static final String SELECT = "SELECT declaration FROM nessy_intent" + WHERE_AGENT;
  private static final String SELECT_VERSION = "SELECT version FROM nessy_intent" + WHERE_AGENT;
  private static final String INSERT =
      "INSERT INTO nessy_intent (agent_type, agent_id, declaration, version) VALUES (?, ?, ?, 1)";
  private static final String UPDATE =
      "UPDATE nessy_intent SET declaration = ?, version = version + 1"
          + WHERE_AGENT
          + " AND version = ?";

  private final JdbcClient jdbc;
  // Unwrapped once, at construction: below this line is SQL, and SQL takes strings. The same
  // shape JdbcNotebook, JdbcPlanStore and TranscriptMemory already use.
  private final String agentType;
  private final String agentId;
  private final Codec<T> codec;

  /** Defaults the stored shape to one {@link Jackson2CodecFactory} over {@code mapper}. */
  public JdbcIntentStore(
      DataSource dataSource,
      AgentType agentType,
      AgentId agentId,
      Class<T> vocabulary,
      ObjectMapper mapper) {
    this(
        dataSource,
        agentType,
        agentId,
        new Jackson2CodecFactory(Objects.requireNonNull(mapper, "mapper must not be null"))
            .create(Objects.requireNonNull(vocabulary, "vocabulary must not be null")));
  }

  public JdbcIntentStore(
      DataSource dataSource, AgentType agentType, AgentId agentId, Codec<T> codec) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null").name();
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null").value();
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  @Override
  public void declare(T declaration) {
    Objects.requireNonNull(declaration, "declaration must not be null");
    String encoded = new String(codec.encode(declaration), StandardCharsets.UTF_8);
    while (true) {
      Optional<Long> version = currentVersion();
      if (version.isEmpty()) {
        try {
          jdbc.sql(INSERT).params(agentType, agentId, encoded).update();
          return;
        } catch (org.springframework.dao.DuplicateKeyException _) {
          // Another caller declared first. Fall through and update its row instead.
          continue;
        }
      }
      if (jdbc.sql(UPDATE).params(encoded, agentType, agentId, version.get()).update() == 1) {
        return;
      }
      // The version moved between the read and the write; read it again and retry.
    }
  }

  @Override
  public Optional<T> latest() {
    return jdbc.sql(SELECT)
        .params(agentType, agentId)
        .query((row, number) -> decode(row.getString("declaration")))
        .optional();
  }

  private Optional<Long> currentVersion() {
    return jdbc.sql(SELECT_VERSION).params(agentType, agentId).query(Long.class).optional();
  }

  private T decode(String declaration) {
    return codec.decode(declaration.getBytes(StandardCharsets.UTF_8));
  }
}
