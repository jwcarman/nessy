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
package org.jwcarman.nessy.examples.newsroom;

import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The one durable substrate this demo needs, built the same way {@code dispatcher} builds its own
 * (a plain {@link DataSource} over Postgres) — just without Spring Boot's autoconfiguration doing
 * it for us, since this module is a plain CLI (chat-cli's shape), not a web app. {@code
 * docker-compose.yml} stands up the same database these defaults point at; {@link #fromEnv()}'s
 * three environment variables let a real deployment point elsewhere without touching code.
 *
 * <p>This is the module's whole restart story (README): {@link org.jwcarman.nessy.Harness}'s store
 * and parks both live in this one database, so killing the process mid-delegation and rerunning
 * {@link Newsroom#main} reattaches to the exact same writer and researcher conversations — nothing
 * about the park, or the notebook, was ever only in this process's memory.
 */
final class NewsroomDatabase {

  private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5435/newsroom";
  private static final String DEFAULT_USER = "newsroom";
  private static final String DEFAULT_PASSWORD = "newsroom";

  private NewsroomDatabase() {}

  /**
   * Builds a {@link DataSource} from {@code NEWSROOM_DB_URL}/{@code NEWSROOM_DB_USER}/{@code
   * NEWSROOM_DB_PASSWORD}, falling back to {@code docker-compose.yml}'s own coordinates when unset
   * — the zero-configuration path for anyone who just ran {@code docker compose up}.
   */
  static DataSource fromEnv() {
    return fromEnv(System.getenv());
  }

  /** The testable seam: {@link #fromEnv()} against an explicit environment map. */
  static DataSource fromEnv(Map<String, String> env) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(env.getOrDefault("NEWSROOM_DB_URL", DEFAULT_URL));
    dataSource.setUser(env.getOrDefault("NEWSROOM_DB_USER", DEFAULT_USER));
    dataSource.setPassword(env.getOrDefault("NEWSROOM_DB_PASSWORD", DEFAULT_PASSWORD));
    return dataSource;
  }
}
