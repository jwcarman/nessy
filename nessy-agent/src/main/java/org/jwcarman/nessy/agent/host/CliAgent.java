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
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.narrate.AwaitingReply;

/**
 * The interactive constant-id host (§1.1, §7.1): one scope for the process, one turn at a time, the
 * caller's thread parks on the reply.
 *
 * <p>Holds its {@link Harness} only to shut it down (fix round 1, item 1): {@code Nessy.cli()}'s
 * build now runs its {@link Harness} through the same compiler every door shares, so it starts a
 * delivery heartbeat exactly like any other harness's — {@link #close()} must quiesce it, or the
 * ephemeral-CLI charter (one turn, then gone) is violated by a stranded daemon thread.
 */
public final class CliAgent implements AutoCloseable {

  private final Agent<String> agent;
  private final Harness<String> harness;
  private final RelayTurnObserver relay;
  private final ExecutorService executor;
  private final boolean ownsExecutor;
  private AwaitingReply current;

  CliAgent(
      Agent<String> agent,
      Harness<String> harness,
      RelayTurnObserver relay,
      ExecutorService executor,
      boolean ownsExecutor) {
    this.agent = Objects.requireNonNull(agent);
    this.harness = Objects.requireNonNull(harness);
    this.relay = Objects.requireNonNull(relay);
    this.executor = Objects.requireNonNull(executor);
    this.ownsExecutor = ownsExecutor;
  }

  public String converse(String line) {
    return converse(line, Duration.ofMinutes(2));
  }

  /**
   * One turn at a time (§7): a still-in-flight turn refuses a new line before it ever reaches the
   * backlog, so a late reply from an abandoned turn can never be misattributed to a fresh waiter.
   */
  public synchronized String converse(String line, Duration timeout) {
    if (current != null && !current.isDone()) {
      throw new IllegalStateException("a previous turn is still in flight; try again shortly");
    }
    var waiter = new AwaitingReply();
    current = waiter;
    relay.set(waiter);
    agent.tell(line);
    return waiter.await(timeout);
  }

  /**
   * The last turn started, if any — package-visible so a test can await its settling directly.
   * {@code null} until the first turn begins.
   */
  synchronized AwaitingReply current() {
    return current;
  }

  /**
   * Shuts down this agent's harness first (its worker heartbeat, spec §4), then closes the owned
   * executor if any — the reverse of build-time construction order, and the ordering the reviewer
   * asked for.
   */
  @Override
  public void close() {
    harness.shutdown();
    if (ownsExecutor) {
      executor.close();
    }
  }
}
