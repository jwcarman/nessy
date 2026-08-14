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
package org.jwcarman.nessy.examples.orderdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The order desk: the broker decides when the agent thinks. No web, no clock, no console loop — the
 * {@code @RabbitListener} container's non-daemon threads are what keep the JVM alive, and each
 * delivery off the {@code orders} queue is a turn (spec §1, §4).
 */
@SpringBootApplication
public class OrderDeskApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderDeskApplication.class, args);
  }
}
