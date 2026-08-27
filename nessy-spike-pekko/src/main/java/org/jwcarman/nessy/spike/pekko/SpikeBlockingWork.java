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
package org.jwcarman.nessy.spike.pekko;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * THROWAWAY SPIKE. Where blocking work actually runs.
 *
 * <p><b>The division of labour, stated once.</b> Pekko schedules FOLDS: every command handler,
 * every {@code persist}, every signal, on its own fork-join dispatcher. We schedule I/O: every
 * model call, every tool execution, on the virtual threads below. Nothing blocking ever touches a
 * Pekko dispatcher, so there is no Pekko threading configuration in this spike at all — no {@code
 * blocking-io-dispatcher}, no {@code fork-join-executor} tuning, no {@code Dispatchers.lookup}, and
 * no JVM flags.
 *
 * <p>The join between the two is {@code ActorContext#pipeToSelf}: the actor hands a {@link
 * java.util.concurrent.CompletionStage} to Pekko, Pekko turns its completion back into an ordinary
 * message, and the actor's thread is never held across the wait. That is why an agent parked on an
 * approval for three days costs nothing — there is no thread anywhere with its stack on it.
 *
 * <p>Virtual threads rather than a bounded pool because the blocking here is a socket wait: a
 * thousand agents each waiting on a model call is a thousand parked continuations, not a thousand
 * OS threads. Concurrency LIMITING is a separate concern and is done structurally, by the size of
 * the worker set behind {@link SpikeModelDesk} — not by starving an executor.
 */
public final class SpikeBlockingWork implements AutoCloseable {

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public ExecutorService executor() {
    return executor;
  }

  @Override
  public void close() {
    executor.shutdown();
  }
}
