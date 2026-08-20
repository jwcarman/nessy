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
package org.jwcarman.nessy.agent.host;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.narrate.AwaitingReply;

/**
 * The interactive constant-id host (§1.1, §7.1): one scope for the process, one turn at a time, the
 * caller's thread parks on the reply.
 */
public final class CliAgent implements AutoCloseable {

  private final Agent<String> agent;
  private final RelayTurnObserver relay;
  private final ExecutorService executor;

  CliAgent(Agent<String> agent, RelayTurnObserver relay, ExecutorService executor) {
    this.agent = Objects.requireNonNull(agent);
    this.relay = Objects.requireNonNull(relay);
    this.executor = Objects.requireNonNull(executor);
  }

  public String converse(String line) {
    return converse(line, Duration.ofMinutes(2));
  }

  public String converse(String line, Duration timeout) {
    var waiter = new AwaitingReply();
    relay.set(waiter);
    try {
      agent.observe(line);
      return waiter.await(timeout);
    } finally {
      relay.clear();
    }
  }

  @Override
  public void close() {
    executor.close();
  }
}
