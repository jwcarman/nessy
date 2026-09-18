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
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the engine hands this module, and somewhere to keep what it writes.
 *
 * <p>H2, because nothing here runs an agent: the intent table belongs to this module and is read
 * and written with ordinary SQL. The engine's own queries are PostgreSQL's, and its tests use real
 * PostgreSQL for exactly that reason.
 */
final class Fixtures {

  static final AgentType TYPE = new AgentType("chat");
  static final AgentId AGENT = new AgentId(UUID.randomUUID());
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Fixtures() {}

  static EmbeddedDatabase freshDatabase() {
    EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    Schemas.initialize(database);
    return database;
  }

  static JdbcIntentStore<Intent> freshStore() {
    return new JdbcIntentStore<>(freshDatabase(), TYPE, Intent.class, MAPPER);
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
