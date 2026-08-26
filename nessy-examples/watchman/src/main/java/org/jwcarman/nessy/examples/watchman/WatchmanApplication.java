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

import java.time.Duration;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The watchman: a Spring Boot agent that lives on a Linux box, does rounds on a timer, proposes
 * remediations that wait days for a human, and exports every span and counter to a Grafana box
 * (spec §2).
 *
 * <p>Nothing here wires a Nessy type by hand. {@code nessy-spring-boot-starter} builds the harness
 * from the beans this application declares — the tools, the grants, the {@code DataSource} — and
 * this class contributes the two things a starter cannot: which host it is running on, and whether
 * it is pretending.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WatchmanApplication {

  /**
   * Feature detection happens HERE, before the context exists, and reaches the context as ordinary
   * default properties. Two consequences worth stating: {@code which} runs once per process rather
   * than once per tool bean, and a {@code @SpringBootTest} — which never calls this method — states
   * the host it is pretending to be by setting {@code watchman.detected.*} instead of shelling out.
   *
   * <p>{@code --scripted} activates the profile of the same name, whose {@code Model} bean wins
   * over the starter's discovery. Same convention as {@code hello} and {@code governed}.
   */
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(WatchmanApplication.class);
    CommandRunner runner = new ProcessRunner(Duration.ofSeconds(30));
    application.setDefaultProperties(new Detect(runner).asProperties());
    if (Arrays.asList(args).contains("--" + Scripted.PROFILE)) {
      application.setAdditionalProfiles(Scripted.PROFILE);
    }
    application.run(args);
  }

  /**
   * The rounds timer, off by default in tests. A cron expression firing in the middle of an
   * assertion is not a test failure worth debugging, so {@code watchman.scheduling.enabled=false}
   * leaves {@link Rounds#doRounds()} as an ordinary method the test calls itself.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      name = "watchman.scheduling.enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Scheduling {}
}
