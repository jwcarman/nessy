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
package org.jwcarman.nessy.engine;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A blocking executor a test can stop, so two instruction batches can be run in a chosen order.
 *
 * <p>Instruction batches are one task each, and nothing orders two of them. That is a real property
 * of the engine and it is why an interleaving that loses a write happens about one run in five —
 * which is no use to a test. Pulling the hand brake queues every task from that moment, letting a
 * test say "run the second batch before the first" on purpose.
 */
final class HandBrake implements Executor {

  private final ExecutorService passingThrough = Executors.newSingleThreadExecutor();
  private final ExecutorService concurrently = Executors.newCachedThreadPool();
  private final Queue<Runnable> held = new ConcurrentLinkedQueue<>();
  private volatile boolean holding;

  @Override
  public void execute(Runnable task) {
    if (holding) {
      held.add(task);
      return;
    }
    passingThrough.execute(task);
  }

  /** From here on, nothing runs until this test says so. */
  void pull() {
    holding = true;
  }

  int pending() {
    return held.size();
  }

  /**
   * Lets everything held run AT ONCE, each on its own thread.
   *
   * <p>Not one after another: the window that loses a write is INSIDE a batch, not between two of
   * them, so the batches have to genuinely overlap for the loss to be reachable.
   */
  void releaseTogether() {
    List<Runnable> tasks = List.copyOf(held);
    held.clear();
    holding = false;
    tasks.forEach(concurrently::execute);
  }

  void shutdown() {
    passingThrough.shutdownNow();
    concurrently.shutdownNow();
  }
}
