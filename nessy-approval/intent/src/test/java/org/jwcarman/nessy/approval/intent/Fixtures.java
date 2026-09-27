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

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the engine hands this module, and somewhere to keep what it writes.
 *
 * <p><b>Real PostgreSQL, not H2.</b> {@link Schemas#initialize} applies every {@code
 * nessy-schema.sql} on the classpath, and this module's test classpath carries the JDBC backend's:
 * it declares a partial index, which H2 cannot parse and which has no portable spelling. H2 was
 * also the wrong database to prove the thing these tests care most about -- a declaration that
 * loses an insert race and retries -- because a compare-and-set that holds on H2 says nothing about
 * the database this store actually runs on.
 *
 * <p>One container for the JVM, and a fresh schema per call to {@link #freshDatabase()}, so a test
 * that asserts a store holds nothing still starts from nothing.
 */
final class Fixtures {

  static final AgentType TYPE = new AgentType("chat");
  static final AgentId AGENT = new AgentId(UUID.randomUUID());
  static final JsonMapper MAPPER = JsonMapper.builder().build();
  static final CodecFactory CODECS = new JacksonCodecFactory(MAPPER);

  private Fixtures() {}

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
  private static final AtomicInteger SCHEMAS = new AtomicInteger();

  static {
    POSTGRES.start();
  }

  /** An empty schema of its own, with every {@code nessy-schema.sql} on the classpath applied. */
  static DataSource freshDatabase() {
    String schema = "intent_" + SCHEMAS.incrementAndGet();
    DriverManagerDataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new JdbcTemplate(database).execute("CREATE SCHEMA " + schema);
    database.setSchema(schema);
    Schemas.initialize(database);
    return database;
  }

  static JdbcIntents<Intent> freshIntents() {
    return new JdbcIntents<>(freshDatabase(), TYPE, Intent.class, CODECS);
  }

  /** The question an approver is asked, for {@link #AGENT}. */
  static ApprovalRequest request() {
    return new ApprovalRequest(
        new AgentType("ops"),
        AGENT,
        new TurnId(1),
        new CallId("c1"),
        new ToolName("restart_prod"),
        "{\"target\":\"prod-eu\"}",
        "restart prod-eu",
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(3600),
        new ReplyToken("nowhere"));
  }

  /** What the engine hands the intent tool when {@link #AGENT} declares something. */
  static <T> ToolCallRequest<T> declaring(T intent) {
    return new ToolCallRequest<>() {
      @Override
      public AgentType agentType() {
        return TYPE;
      }

      @Override
      public AgentId agentId() {
        return AGENT;
      }

      @Override
      public TurnId turn() {
        return new TurnId(1);
      }

      @Override
      public CallId callId() {
        return new CallId("c1");
      }

      @Override
      public ToolName toolName() {
        return new ToolName("declare-intent");
      }

      @Override
      public T input() {
        return intent;
      }

      @Override
      public Instant deadline() {
        return Instant.now().plusSeconds(30);
      }

      @Override
      public ReplyToken replyToken() {
        return new ReplyToken("unused-by-a-tool-that-never-defers");
      }
    };
  }
}
