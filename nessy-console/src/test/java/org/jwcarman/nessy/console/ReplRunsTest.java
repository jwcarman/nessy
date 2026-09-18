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
package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole console, once: a provider found through the Boot context it raises, a database handed
 * in, the schema applied, the harness built, and the loop run until the person leaves. The provider
 * points at a closed port, so a line typed into it fails fast and the failure is narrated rather
 * than thrown.
 */
@DisplayName("A console application with everything configured")
class ReplRunsTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final Map<String, String> PROPERTIES =
      Map.of(
          "openai.api-key", "not-needed",
          "openai.base-url", "http://127.0.0.1:1/v1",
          "nessy.model", "a-model");

  @BeforeAll
  static void start() {
    POSTGRES.start();
    PROPERTIES.forEach(System::setProperty);
  }

  @AfterAll
  static void stop() {
    PROPERTIES.keySet().forEach(System::clearProperty);
    POSTGRES.stop();
  }

  private record Ping() {}

  /** A tool granted on the way in, so the grant path of the bootstrap is walked. */
  private static final class PingTool implements org.jwcarman.nessy.api.tool.Tool<Ping> {
    @Override
    public Class<Ping> inputType() {
      return Ping.class;
    }

    @Override
    public org.jwcarman.nessy.api.tool.ToolName name() {
      return new org.jwcarman.nessy.api.tool.ToolName("ping");
    }

    @Override
    public String description() {
      return "pings";
    }

    @Override
    public org.jwcarman.nessy.api.Awaited<org.jwcarman.nessy.api.tool.ToolResult> call(
        org.jwcarman.nessy.api.tool.ToolCallRequest<Ping> request) {
      return org.jwcarman.nessy.api.Awaited.ready(
          org.jwcarman.nessy.api.tool.ToolResult.ok(
              new org.jwcarman.nessy.api.block.Block.Text("pong")));
    }
  }

  private static PGSimpleDataSource database() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  @Test
  @DisplayName("prints its banner, takes a line, reports the turn, and leaves when told")
  void it_runs_the_loop() {
    FakeConsole console = new FakeConsole("hello there", "/exit");
    ReplConfig config =
        new ReplConfig()
            .banner("nessy test")
            .farewell("bye.")
            .dataSource(database())
            .maxTokens(64)
            .tool(new PingTool())
            .systemPrompt("You are a test assistant.");

    Repl.run(config, console);

    assertThat(console.written()).startsWith("nessy test").contains("bye.");
    // The provider is a closed port, so the one turn it was asked for failed, and said so.
    assertThat(console.written()).contains("failed");
  }

  @Test
  @DisplayName("says so when no model is named")
  void it_says_when_there_is_no_model() {
    System.clearProperty("nessy.model");
    try {
      FakeConsole console = new FakeConsole("/exit");
      Repl.run(new ReplConfig().dataSource(database()), console);
      assertThat(console.written()).contains("no model is configured");
    } finally {
      System.setProperty("nessy.model", "a-model");
    }
  }

  @Test
  @DisplayName("says so when no database can be found")
  void it_says_when_there_is_no_database() {
    FakeConsole console = new FakeConsole("/exit");

    Repl.run(new ReplConfig(), console);

    assertThat(console.written()).contains("no database is configured");
  }
}
