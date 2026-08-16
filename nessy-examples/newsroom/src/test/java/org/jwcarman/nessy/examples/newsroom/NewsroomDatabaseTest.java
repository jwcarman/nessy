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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class NewsroomDatabaseTest {

  @Test
  void an_empty_environment_falls_back_to_the_docker_compose_coordinates() {
    DataSource dataSource = NewsroomDatabase.fromEnv(Map.of());

    PGSimpleDataSource pg = (PGSimpleDataSource) dataSource;
    assertThat(pg.getUrl()).startsWith("jdbc:postgresql://localhost:5435/newsroom");
    assertThat(pg.getUser()).isEqualTo("newsroom");
  }

  @Test
  void an_explicit_environment_overrides_every_coordinate() {
    Map<String, String> env =
        Map.of(
            "NEWSROOM_DB_URL", "jdbc:postgresql://db.example.com:5432/prod",
            "NEWSROOM_DB_USER", "someone",
            "NEWSROOM_DB_PASSWORD", "secret");

    DataSource dataSource = NewsroomDatabase.fromEnv(env);

    PGSimpleDataSource pg = (PGSimpleDataSource) dataSource;
    assertThat(pg.getUrl()).startsWith("jdbc:postgresql://db.example.com:5432/prod");
    assertThat(pg.getUser()).isEqualTo("someone");
  }
}
