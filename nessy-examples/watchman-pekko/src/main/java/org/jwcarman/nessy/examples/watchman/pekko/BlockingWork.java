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
 * <p>Pekko schedules folds; we schedule I/O — that was the whole story before this branch, and it
 * is no longer the whole story. {@link AgentActor}'s command handler now makes four blocking
 * substrate calls directly, on the Pekko dispatcher: {@code Backlogs#ingest} in {@code onObserve},
 * {@code Claims#put} once per tool call in {@code onModelReplied}, {@code Claims#deleteTurn} in
 * {@code startTurnIfWork}, and {@code Memories#forAgent(..).remember} also in {@code
 * startTurnIfWork}. None of those touches {@link #executor()} — they run inline, on whatever thread
 * the dispatcher handed the actor.
 *
 * <p>That is a starvation risk this application has no configuration to cushion: this application
 * still has no {@code blocking-io-dispatcher}, no {@code fork-join-executor} tuning, no {@code
 * Dispatchers.lookup} — every {@link AgentActor} shares Pekko's default dispatcher with {@link
 * ModelWorker} and {@link ModelDesk}'s own folds. N agents blocked on a slow substrate call (a
 * stalled connection pool, a saturated database) each occupy one of that dispatcher's threads for
 * as long as the call takes, and every fold sharing the pool — including the desk's own bookkeeping
 * — waits behind them. Virtual threads are what keep the WORKERS behind this class off that
 * dispatcher; they do nothing for calls the actor makes itself.
 *
 * <p>Virtual threads because the blocking is a socket wait or a subprocess wait. Concurrency for
 * work routed through this class is limited structurally, by the size of the worker set behind
 * {@link ModelDesk} — the four call sites above are not routed through it, and are not bounded by
 * it.
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
