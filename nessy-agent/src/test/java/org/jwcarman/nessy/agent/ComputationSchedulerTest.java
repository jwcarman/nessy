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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.ResultTtl;

/**
 * {@link ComputationScheduler} is a small class, but one of its properties is a silent-forever
 * failure, and most of this file is about that (continuum-adoption spec §7). Driven with an
 * injected {@link ScheduledExecutorService} fake rather than real sleeps — {@code
 * MutableInstantSource} in {@code continuum-testing} is the house pattern for controlling time; the
 * equivalent here is {@link RecordingScheduler}, which records what was scheduled at what cadence
 * and lets a test fire tasks on demand.
 */
class ComputationSchedulerTest {

  /**
   * Records every {@code scheduleWithFixedDelay} call and lets a test fire the scheduled task on
   * demand; throws on {@code scheduleAtFixedRate} so a wrong scheduling method fails the test at
   * the moment it is written, not as a subtle production behaviour (spec §7: fixed-delay, so a slow
   * batch cannot stack overlapping runs of one pump on one node).
   */
  private static final class RecordingScheduler implements ScheduledExecutorService {

    record Scheduled(Runnable task, long initialDelay, long period, TimeUnit unit) {}

    final List<Scheduled> fixedDelay = new ArrayList<>();
    final AtomicBoolean shutdown = new AtomicBoolean();

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      fixedDelay.add(new Scheduled(command, initialDelay, delay, unit));
      return null; // no caller reads it; if that changes, return a fake future
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new AssertionError("fixed-rate scheduling is not permitted; see spec §7");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Future<?> submit(Runnable task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
      shutdown.set(true);
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown.set(true);
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isTerminated() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A {@link ComputationPump} fake whose every method runs one shared action, counts calls, and
   * records which of the six methods was invoked — fix round 1 item 6: pure counts alone cannot
   * tell six distinct pumps from six copies of one (a {@code register} that copy-pasted {@code
   * drainApprovals} twice and dropped {@code expireTools} would pass every count-only assertion,
   * and a dropped expire pump means nothing ever times out).
   */
  private static final class CountingWorker implements ComputationPump {

    final AtomicInteger attempts = new AtomicInteger();
    final List<String> invokedMethods = new ArrayList<>();
    private final Runnable action;

    CountingWorker(Runnable action) {
      this.action = action;
    }

    private int run(String method) {
      attempts.incrementAndGet();
      invokedMethods.add(method);
      action.run();
      return 0;
    }

    @Override
    public int drainApprovals(BatchSize batchSize) {
      return run("drainApprovals");
    }

    @Override
    public int drainTools(BatchSize batchSize) {
      return run("drainTools");
    }

    @Override
    public int expireApprovals(BatchSize batchSize) {
      return run("expireApprovals");
    }

    @Override
    public int expireTools(BatchSize batchSize) {
      return run("expireTools");
    }

    @Override
    public int purgeApprovals(BatchSize batchSize, ResultTtl ttl) {
      return run("purgeApprovals");
    }

    @Override
    public int purgeTools(BatchSize batchSize, ResultTtl ttl) {
      return run("purgeTools");
    }
  }

  private final RecordingScheduler fake = new RecordingScheduler();
  private final CountingWorker worker = new CountingWorker(() -> {});

  @Test
  void registeringAWorkerSchedulesSixPumps() {
    var scheduler = new ComputationScheduler(fake);

    scheduler.register(worker);

    assertThat(fake.fixedDelay).hasSize(6);
  }

  @Test
  void theSixScheduledPumpsAreSixDistinctMethodsNotSixCopiesOfOne() {
    var scheduler = new ComputationScheduler(fake);

    scheduler.register(worker);
    fake.fixedDelay.forEach(scheduled -> scheduled.task().run());

    assertThat(worker.invokedMethods).hasSize(6);
    assertThat(worker.invokedMethods)
        .containsExactlyInAnyOrder(
            "drainApprovals",
            "drainTools",
            "expireApprovals",
            "expireTools",
            "purgeApprovals",
            "purgeTools");
  }

  @Test
  void everyPumpIsScheduledWithFixedDelay() {
    var scheduler = new ComputationScheduler(fake);

    scheduler.register(worker);

    assertThat(fake.fixedDelay).isNotEmpty();
    assertThat(fake.fixedDelay).allSatisfy(s -> assertThat(s.period()).isPositive());
  }

  @Test
  void aPumpThatThrowsKeepsItsSchedule() {
    var failing =
        new CountingWorker(
            () -> {
              throw new IllegalStateException("boom");
            });
    var scheduler = new ComputationScheduler(fake);
    scheduler.register(failing);
    Runnable pump = fake.fixedDelay.getFirst().task();

    pump.run();
    pump.run();

    assertThat(failing.attempts).hasValue(2);
  }

  @Test
  void anErrorIsNotSwallowed() {
    var failing =
        new CountingWorker(
            () -> {
              throw new OutOfMemoryError("not yours");
            });
    var scheduler = new ComputationScheduler(fake);
    scheduler.register(failing);
    Runnable pump = fake.fixedDelay.getFirst().task();

    assertThatThrownBy(pump::run).isInstanceOf(OutOfMemoryError.class);
  }

  @Test
  void closingShutsTheSchedulerDown() {
    var scheduler = new ComputationScheduler(fake);
    scheduler.register(worker);

    scheduler.close();

    assertThat(fake.shutdown).isTrue();
  }

  @Test
  void twoWorkersShareOnePoolAndScheduleTwelvePumps() {
    var scheduler = new ComputationScheduler(fake);
    var workerA = new CountingWorker(() -> {});
    var workerB = new CountingWorker(() -> {});

    scheduler.register(workerA);
    scheduler.register(workerB);

    assertThat(fake.fixedDelay).hasSize(12);
  }
}
