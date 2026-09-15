package org.jwcarman.nessy.memory.notebook;

import java.time.Instant;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * What the engine hands a running tool, and somewhere to keep what the tool writes.
 *
 * <p>No mocking library, and none needed: a {@link ToolCallRequest} is six values and a database is
 * one builder call.
 *
 * <p><b>H2, because nothing here runs an agent.</b> These tables belong to this module and are read
 * and written with ordinary SQL. The engine's own queries are PostgreSQL's, and its tests use real
 * PostgreSQL for exactly that reason.
 */
final class Calls {

  static final AgentType TYPE = new AgentType("chat");

  private Calls() {}

  static AgentId agent() {
    return new AgentId(UUID.randomUUID());
  }

  /** Every {@code nessy-schema.sql} on the classpath, which here is this module's own. */
  static EmbeddedDatabase freshDatabase() {
    EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    Schemas.initialize(database);
    return database;
  }

  static <I> ToolCallRequest<I> by(AgentId agentId, I input) {
    return new ToolCallRequest<>() {
      @Override
      public AgentType agentType() {
        return TYPE;
      }

      @Override
      public AgentId agentId() {
        return agentId;
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
        return new ToolName("a_tool");
      }

      @Override
      public I input() {
        return input;
      }

      @Override
      public Instant deadline() {
        return Instant.now().plusSeconds(30);
      }

      @Override
      public ReplyToken replyToken() {
        return new ReplyToken("unused");
      }
    };
  }
}
