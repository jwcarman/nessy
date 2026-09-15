package org.jwcarman.nessy.memory.plan;

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
 * What the engine hands a running tool, and somewhere to keep what the tool writes.
 *
 * <p>No mocking library, and none needed: a {@link ToolCallRequest} is six values and a database is
 * one builder call.
 *
 * <p><b>Real PostgreSQL, not H2.</b> These tables are read and written with ordinary SQL, and H2
 * ran it happily -- including a bare UUID bound to a TEXT column, which PostgreSQL refuses and
 * which the first application to open a notebook over a real database found for us. One container
 * for the JVM; agents are addressed by fresh random ids, so tests do not have to clean up after
 * each other.
 */
final class Calls {

  static final AgentType TYPE = new AgentType("chat");

  private Calls() {}

  static AgentId agent() {
    return new AgentId(UUID.randomUUID());
  }

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  /** The shared database, with every {@code nessy-schema.sql} on the classpath applied to it. */
  static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
