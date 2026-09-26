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

import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.springframework.dao.DuplicateKeyException;
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
public final class JdbcIntents<T> implements Intents<T> {

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
  // shape JdbcNotebook, JdbcPlans and TranscriptMemory already use.
  private final String agentType;
  private final Codec<T> codec;

  /**
   * Defaults the stored shape to whatever {@code codecs} builds for {@code vocabulary} -- a Spring
   * application hands in the context's one {@link CodecFactory} bean, so this table is written and
   * read exactly the way every other store is, storage transform included.
   */
  public JdbcIntents(
      DataSource dataSource, AgentType agentType, Class<T> vocabulary, CodecFactory codecs) {
    this(
        dataSource,
        agentType,
        Objects.requireNonNull(codecs, "codecs must not be null")
            .create(Objects.requireNonNull(vocabulary, "vocabulary must not be null")));
  }

  public JdbcIntents(DataSource dataSource, AgentType agentType, Codec<T> codec) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null").value();
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  /**
   * The type and id columns are text; an id crosses into SQL as its canonical string, as everywhere
   * else. The declaration column is bytes -- the codec's, not a string's.
   */
  private static String key(AgentId agentId) {
    return Objects.requireNonNull(agentId, "agentId must not be null").value().toString();
  }

  @Override
  public void declare(AgentId agent, T declaration) {
    Objects.requireNonNull(declaration, "declaration must not be null");
    String agentId = key(agent);
    byte[] encoded = codec.encode(declaration);
    while (true) {
      Optional<Long> version = currentVersion(agentId);
      if (version.isEmpty()) {
        try {
          jdbc.sql(INSERT).params(agentType, agentId, encoded).update();
          return;
        } catch (DuplicateKeyException _) {
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
  public Optional<T> latest(AgentId agent) {
    return jdbc.sql(SELECT)
        .params(agentType, key(agent))
        .query((row, number) -> codec.decode(row.getBytes("declaration")))
        .optional();
  }

  private Optional<Long> currentVersion(String agentId) {
    return jdbc.sql(SELECT_VERSION).params(agentType, agentId).query(Long.class).optional();
  }
}
