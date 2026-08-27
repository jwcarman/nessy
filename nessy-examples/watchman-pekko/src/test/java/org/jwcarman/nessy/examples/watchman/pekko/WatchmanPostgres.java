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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.typesafe.config.Config;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** The running watchman-postgres, in its own schema so the sibling application is untouched. */
public final class WatchmanPostgres {

  public static final String URL =
      "jdbc:postgresql://localhost:5432/watchman?currentSchema=watchman_pekko";
  public static final String USER = "watchman";
  public static final String PASSWORD = "watchman";

  private WatchmanPostgres() {}

  public static Config config() {
    return PekkoConfigBridge.build("watchman-pekko", URL, USER, PASSWORD);
  }

  public static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(URL);
    dataSource.setUser(USER);
    dataSource.setPassword(PASSWORD);
    return dataSource;
  }

  public static WatchmanActorSystem start(WatchmanModel model) {
    WatchmanActorSystem actors =
        new WatchmanActorSystem(
            config(),
            model,
            new FakeRunner(),
            new Traces(io.opentelemetry.api.OpenTelemetry.noop()),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(15));
    actors.start();
    return actors;
  }
}
