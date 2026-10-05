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
package org.jwcarman.nessy.planning;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

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

  /** The codec factory an application would hand the store: Jackson, nothing appended. */
  static CodecFactory codecs() {
    return new JacksonCodecFactory(JsonMapper.builder().build());
  }

  /**
   * The same factory with every byte XORed after Jackson, so a stored value is visibly not what was
   * written. Not a cipher; enough to make a row unreadable to anyone who does not undo it.
   */
  static CodecFactory transforming() {
    CodecFactory jackson = codecs();
    Codec<byte[]> xor =
        new Codec<>() {
          @Override
          public byte[] encode(byte[] bytes) {
            return flip(bytes);
          }

          @Override
          public byte[] decode(byte[] bytes) {
            return flip(bytes);
          }
        };
    return new CodecFactory() {
      @Override
      public <T> Codec<T> create(TypeRef<T> type) {
        return jackson.create(type).andThen(xor);
      }
    };
  }

  private static byte[] flip(byte[] bytes) {
    byte[] out = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      out[i] = (byte) (bytes[i] ^ 0x5A);
    }
    return out;
  }

  /** What a column holds, as text, read with plain JDBC and no codec. */
  static String raw(DataSource database, String sql, Object... params) {
    byte[] bytes = JdbcClient.create(database).sql(sql).params(params).query(byte[].class).single();
    return new String(bytes, StandardCharsets.UTF_8);
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
      public IdempotencyKey idempotencyKey() {

        return IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
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
    };
  }
}
