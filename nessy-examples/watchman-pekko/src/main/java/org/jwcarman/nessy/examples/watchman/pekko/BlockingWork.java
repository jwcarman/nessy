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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where blocking work runs: {@code df}, {@code docker}, {@code fstrim}, and every model call.
 *
 * <p>Pekko schedules folds; we schedule I/O. Nothing blocking ever touches a Pekko dispatcher, so
 * this application has no Pekko threading configuration at all — no {@code blocking-io-dispatcher},
 * no {@code fork-join-executor} tuning, no {@code Dispatchers.lookup}.
 *
 * <p>Virtual threads because the blocking is a socket wait or a subprocess wait. Concurrency is
 * limited structurally instead, by the size of the worker set behind {@link ModelDesk}.
 */
public final class BlockingWork implements AutoCloseable {

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public ExecutorService executor() {
    return executor;
  }

  @Override
  public void close() {
    executor.shutdown();
  }
}
