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
package org.jwcarman.nessy.examples.watchman;

import com.typesafe.config.Config;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.agent.Coalescer;
import org.jwcarman.nessy.engine.AgentModel;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Claims;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.postgresql.ds.PGSimpleDataSource;

/** The running watchman-postgres, in its own schema so the sibling application is untouched. */
public final class WatchmanPostgres {

  public static final String URL =
      "jdbc:postgresql://localhost:5432/watchman?currentSchema=watchman";
  public static final String USER = "watchman";
  public static final String PASSWORD = "watchman";

  private WatchmanPostgres() {}

  public static Config config() {
    return PekkoConfigBridge.build("watchman", URL, USER, PASSWORD);
  }

  public static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(URL);
    dataSource.setUser(USER);
    dataSource.setPassword(PASSWORD);
    return dataSource;
  }

  /** One substrate over the shared DataSource — what the engine stores everything through. */
  public static org.jwcarman.nessy.spi.substrate.Substrate substrate() {
    return new org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate(dataSource(), Clock.systemUTC());
  }

  public static Memories memories() {
    return new Memories(
        new org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate(dataSource(), Clock.systemUTC()), 8000);
  }

  public static Claims claims() {
    return new Claims(
        new org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate(dataSource(), Clock.systemUTC()));
  }

  /** Remember a user turn the way the cron does, before telling the agent. */
  public static void observe(String agentId, String text) {
    memories()
        .forAgent(agentId)
        .remember(
            new org.jwcarman.nessy.spi.Remembrance.UserMessage(
                org.jwcarman.nessy.api.Identifiers.next(),
                org.jwcarman.nessy.api.message.Message.user(text)));
  }

  /** Tool results by call id, straight out of Memory. */
  public static java.util.Map<String, String> results(String agentId) {
    java.util.Map<String, String> byCall = new java.util.LinkedHashMap<>();
    for (var message : memories().everything(agentId).messages()) {
      for (var block : message.content()) {
        if (block instanceof org.jwcarman.nessy.api.message.ToolResultBlock result) {
          byCall.putIfAbsent(result.toolUseId(), result.text());
        }
      }
    }
    return byCall;
  }

  public static WatchmanActorSystem start(AgentModel model) {
    return start(model, memories());
  }

  public static WatchmanActorSystem start(AgentModel model, Memories memories) {
    WatchmanActorSystem actors =
        new WatchmanActorSystem(
            config(),
            model,
            new FakeRunner(),
            substrate(),
            Coalescer.none(),
            8000,
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(15));
    actors.start();
    return actors;
  }
}
