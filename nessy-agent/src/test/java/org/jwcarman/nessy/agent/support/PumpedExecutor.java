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
package org.jwcarman.nessy.agent.support;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Tasks queue; the test pumps until quiet. Deterministic asynchrony, no threads, from the pumping
 * thread's own point of view (§3.2) — but {@link #execute} itself is called cross-thread now that
 * {@code DeliveryWorker#nudge()} submits work to a real {@code ComputationScheduler} pool thread
 * (continuum-adoption spec §7, fix round 1 item 1): a scheduler thread can be mid-{@link #execute}
 * while the test's own thread is mid-{@link #pumpUntilQuiet()}. {@link ConcurrentLinkedQueue} gives
 * that cross-thread {@code add}/{@code poll} pair the happens-before edge an {@link
 * java.util.ArrayDeque} never promised, without changing this class's single-threaded-caller
 * semantics at all.
 */
public final class PumpedExecutor implements Executor {

  private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();

  @Override
  public void execute(Runnable task) {
    queue.add(task);
  }

  public void pumpUntilQuiet() {
    while (!queue.isEmpty()) {
      queue.poll().run();
    }
  }

  public boolean isQuiet() {
    return queue.isEmpty();
  }
}
