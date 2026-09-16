package org.jwcarman.nessy.memory.episodic;

import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the engine hands a running tool, and somewhere to keep what the tool writes: one PostgreSQL
 * for the JVM, agents addressed by fresh random ids so tests need not clean up after each other.
 */
final class Calls {

  static final AgentType TYPE = new AgentType("chat");

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private Calls() {}

  static AgentId agent() {
    return new AgentId(UUID.randomUUID());
  }

  /** The shared database, with every {@code nessy-schema.sql} on the classpath applied to it. */
  static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  static <I> ToolCallRequest<I> by(AgentId agentId, long turn, I input) {
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
        return new TurnId(turn);
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
