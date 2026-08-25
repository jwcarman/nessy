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
package org.jwcarman.nessy.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.ResultTtl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small, platform-threaded {@link ScheduledExecutorService} driving every worker registered to
 * THIS instance's pumps (continuum-adoption spec §7) — six per worker: deliver, expire, and purge,
 * once each for the approval and tool kinds, all sharing this one pool rather than each pump
 * getting its own thread. This replaces {@code DeliveryWorker}'s old per-{@code Harness} daemon
 * heartbeat thread. One caveat honestly stated: {@link Harness#of} builds one {@code
 * ComputationScheduler} per {@code Harness} — there is no process-wide holder shared across
 * multiple agent types today — so thread count stops scaling with the number of WORKERS on one
 * harness (proven by two workers registered to one scheduler sharing its pool), but still scales
 * with the number of distinct {@code Harness} instances a process builds, same as the heartbeat it
 * replaces. A caller-supplied, process-wide scheduler would close that gap; it is a new public knob
 * this class does not offer (continuum-adoption spec §7's decision list, deferred).
 *
 * <p><b>Fixed-delay, not fixed-rate</b> ({@link ScheduledExecutorService#scheduleWithFixedDelay}):
 * a slow batch must not stack overlapping runs of the same pump on one node — Continuum's own
 * guidance recommends fixed-delay for exactly this reason.
 *
 * <p><b>Platform threads, not virtual ones.</b> This sidesteps the JDBC driver thread-pinning
 * question a virtual-thread pool would carry (fixed by JEP 491 in JDK 24, but not worth depending
 * on here).
 *
 * <p><b>A throwing pump keeps its schedule.</b> {@link ScheduledExecutorService} silently cancels a
 * repeating task that throws — no log, no retry, nothing announced — so a pump that throws once (a
 * transient store error, one poisoned delivery that fails to decode) would otherwise stop running
 * for the life of the process, and the symptom is not an error but an absence: deliveries simply
 * stop being delivered. Every scheduled pump is therefore wrapped to catch {@link RuntimeException}
 * and log it rather than propagate; an {@link Error} (an {@link OutOfMemoryError}, say) is not a
 * pump's to handle and is left to propagate.
 *
 * <p>Also usable as a plain {@link Executor}: {@code DeliveryWorker#nudge()} submits one-off
 * approval/tool drain passes here rather than running them on the caller's thread (spec §7), so an
 * approval or completion arriving from a UI or HTTP handler returns immediately rather than
 * blocking for as long as a granted inline tool takes to run — and the submitted work shares this
 * same small pool rather than spinning up another.
 */
public final class ComputationScheduler implements AutoCloseable, Executor {

  private static final Logger log = LoggerFactory.getLogger(ComputationScheduler.class);

  private static final BatchSize DELIVER_BATCH = BatchSize.of(25);
  private static final BatchSize EXPIRE_BATCH = BatchSize.of(12);
  private static final BatchSize PURGE_BATCH = BatchSize.of(200);
  private static final ResultTtl RESULT_TTL = ResultTtl.ofHours(1);

  private static final Duration DELIVER_DELAY = Duration.ofSeconds(1);
  private static final Duration APPROVAL_EXPIRE_DELAY = Duration.ofMinutes(1);
  private static final Duration TOOL_EXPIRE_DELAY = Duration.ofSeconds(15);
  private static final Duration PURGE_DELAY = Duration.ofMinutes(10);

  /**
   * Two, not one (fix round 1 item 3: dropped from an eager four): no pump does the work any more —
   * both consumers only fold (approval-lifecycle spec §5) — but a fold still reads and CAS-writes a
   * scope, so a single thread would let one slow substrate wedge the expire and purge pumps behind
   * it. Two is enough — fixed-delay is exactly the discipline (§7) that makes one runner per pump
   * unnecessary.
   */
  private static final int POOL_SIZE = 2;

  private final ScheduledExecutorService scheduler;

  /**
   * The production constructor: a small, daemon-threaded, platform-thread pool of its own — daemon
   * so it never blocks process shutdown, since the harness is immortal and owns no lifecycle door
   * of its own beyond {@link Harness#shutdown()}. Threads start lazily, on first task fire, like
   * any other {@link java.util.concurrent.ScheduledThreadPoolExecutor}.
   */
  public ComputationScheduler() {
    this(Executors.newScheduledThreadPool(POOL_SIZE, daemonThreadFactory()));
  }

  /**
   * Test seam: an injected {@link ScheduledExecutorService} — a hand-written fake in tests, so
   * {@link #register}'s shape and a throwing pump's schedule survival can be proven without real
   * threads or real sleeps.
   *
   * @param scheduler the executor every registered worker's pumps share
   */
  ComputationScheduler(ScheduledExecutorService scheduler) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
  }

  private static ThreadFactory daemonThreadFactory() {
    AtomicInteger count = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, "nessy-pump-" + count.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Registers {@code worker}'s six pumps onto the shared schedule — deliver, expire, and purge,
   * once each for the approval and tool kinds — each with {@code scheduleWithFixedDelay}.
   *
   * @param worker the worker to pump
   */
  public void register(ComputationPump worker) {
    Objects.requireNonNull(worker, "worker must not be null");
    schedule(() -> worker.drainApprovals(DELIVER_BATCH), DELIVER_DELAY);
    schedule(() -> worker.drainTools(DELIVER_BATCH), DELIVER_DELAY);
    schedule(() -> worker.expireApprovals(EXPIRE_BATCH), APPROVAL_EXPIRE_DELAY);
    schedule(() -> worker.expireTools(EXPIRE_BATCH), TOOL_EXPIRE_DELAY);
    schedule(() -> worker.purgeApprovals(PURGE_BATCH, RESULT_TTL), PURGE_DELAY);
    schedule(() -> worker.purgeTools(PURGE_BATCH, RESULT_TTL), PURGE_DELAY);
  }

  private void schedule(Runnable task, Duration delay) {
    long millis = delay.toMillis();
    scheduler.scheduleWithFixedDelay(guarded(task), millis, millis, TimeUnit.MILLISECONDS);
  }

  /**
   * Submits {@code task} to the shared pool, guarded the same way a scheduled pump is — a thrown
   * {@link RuntimeException} is caught and logged on the pool thread that runs {@code task}, so it
   * never reaches a caller of {@code task} (there is no such caller here; {@code task} runs later,
   * asynchronously). The only exception THIS method itself can throw is a {@link
   * RejectedExecutionException} from {@code scheduler.execute} once {@link #close()} has run — a
   * shut-down scheduler discarding a late nudge (e.g. a container destroy callback racing an HTTP
   * approval) is benign, so that is caught here too and logged at debug, not propagated.
   *
   * @param task the work to run
   */
  @Override
  public void execute(Runnable task) {
    try {
      scheduler.execute(guarded(task));
    } catch (RejectedExecutionException e) {
      log.debug("a submitted task was rejected; the scheduler has been closed", e);
    }
  }

  /**
   * {@link ScheduledExecutorService} silently cancels a repeating task that throws (see the class
   * javadoc). Catches {@link RuntimeException} only: an {@link Error} is not a pump's to handle and
   * propagates. Runs on a pool thread, not the submitter's — this guard is what keeps a scheduled
   * pump's own throw from reaching anyone; {@link #execute}'s {@code RejectedExecutionException}
   * catch is a separate concern (submission itself failing, before {@code task} ever runs).
   */
  private static Runnable guarded(Runnable task) {
    return () -> {
      try {
        task.run();
      } catch (RuntimeException e) {
        log.warn("a scheduled pump failed; it will run again on its next tick", e);
      }
    };
  }

  /**
   * Shuts down the shared pool; scheduled pumps stop, and any in-flight one finishes on its own.
   */
  @Override
  public void close() {
    scheduler.shutdown();
  }
}
