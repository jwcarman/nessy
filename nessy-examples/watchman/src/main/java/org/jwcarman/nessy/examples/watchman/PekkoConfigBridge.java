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
import com.typesafe.config.ConfigFactory;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pushes Spring's datasource credentials into Pekko's own connection pool.
 *
 * <p><b>Why this exists.</b> The durable-state plugin runs a SECOND pool against the same database,
 * through Slick, which reads its settings from Pekko config rather than from Spring. Without this
 * bridge the credentials would live in two files and drift — and the way you find out is an
 * application that starts, serves pages, and cannot persist a single turn.
 *
 * <p>{@code application.yml} is the one place they are written. This reads them back out and hands
 * them to the starter as a {@link Config} bean, which layers over {@code watchman.conf} and the
 * starter's own {@code reference.conf}.
 */
@Configuration(proxyBeanMethods = false)
public class PekkoConfigBridge {

  private static final String SHARED = "pekko-persistence-jdbc.shared-databases.watchman.db.";

  @Bean
  public Config pekkoConfig(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String user,
      @Value("${spring.datasource.password}") String password) {
    return ConfigFactory.parseMap(
            Map.of(SHARED + "url", url, SHARED + "user", user, SHARED + "password", password))
        .withFallback(ConfigFactory.load("watchman"));
  }
}
