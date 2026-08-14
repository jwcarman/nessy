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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The night watchman: a long-lived app whose turns are initiated by the clock (spec §1). No web, no
 * console loop — {@code @EnableScheduling}'s non-daemon scheduler thread is what keeps the JVM
 * alive, and the {@code @Scheduled} round in {@code Watchman} is the only driver. The log is the
 * UI.
 */
@SpringBootApplication
@EnableScheduling
public class NightWatchmanApplication {

  public static void main(String[] args) {
    SpringApplication.run(NightWatchmanApplication.class, args);
  }
}
