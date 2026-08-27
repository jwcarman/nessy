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
import com.typesafe.config.ConfigFactory;
import java.util.Map;

/**
 * HOCON meets {@code application.yml}, and the honest answer is: they do not merge, so something
 * has to carry values across. This class is that something, and it is deliberately the ONLY place
 * it happens.
 *
 * <p><b>The split we settled on.</b> Pekko-shaped settings that a Spring developer never touches —
 * serializers, persistence plugin ids, coordinated shutdown — live in {@code watchman-pekko.conf},
 * where HOCON's substitutions and includes work properly. Operational settings that an operator
 * DOES touch — the database, the cron, timeouts — live in {@code application.yml} where every other
 * Spring property lives, and the three that Pekko also needs are pushed across here.
 *
 * <p><b>Why not put all of Pekko's config in yaml.</b> It is possible: bind a {@code Map<String,
 * Object>} and hand it to {@code ConfigFactory.parseMap}. It buys one file for a reader and costs
 * HOCON semantics — no {@code include}, no {@code ${}} substitution — and it needs a normalisation
 * step, because Spring's relaxed binding turns a HOCON list into a comma-joined String that Pekko
 * will then reject. We chose the two files.
 *
 * <p><b>So the papercut is real and this is its exact size:</b> the datasource is configured twice,
 * once for Spring's {@code DataSource} and once for Pekko's Slick pool, from one set of Spring
 * properties. That is not a bridging failure — it is the deeper fact that {@code
 * pekko-persistence-jdbc} builds its OWN HikariCP pool from HOCON and has no way to accept a {@code
 * javax.sql.DataSource} that already exists. Two pools against one database is the cost of the
 * plugin, and the reason a DurableStateStore over our own Substrate keeps looking attractive.
 */
public final class PekkoConfigBridge {

  private PekkoConfigBridge() {}

  /**
   * @param resource the HOCON file holding everything Pekko-shaped
   * @param url the JDBC url Spring is configured with, pushed into Pekko's Slick pool
   */
  public static Config build(String resource, String url, String user, String password) {
    Config fromSpring =
        ConfigFactory.parseMap(
            Map.of(
                "pekko-persistence-jdbc.shared-databases.watchman.db.url", url,
                "pekko-persistence-jdbc.shared-databases.watchman.db.user", user,
                "pekko-persistence-jdbc.shared-databases.watchman.db.password", password),
            "watchman application.yml");
    return fromSpring.withFallback(ConfigFactory.load(resource)).resolve();
  }
}
