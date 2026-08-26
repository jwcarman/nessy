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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * {@code long_job} — the soak's deferred-tool proof (spec §2.1).
 *
 * <p>The assertion that matters is the third one: <b>the id the tool handed out is the id the
 * completion door is later told about</b>, from a thread that is not the one the tool ran on. That
 * is the whole contract of {@code ToolContext.defer()} — an external system can hold an id across
 * time it does not control and answer with it later — and everything else here is scaffolding for
 * being able to state it.
 */
class LongJobTest {

  private final FakeRunner runner = new FakeRunner().blocking().answering("fstrim", "/: 12 GiB");
  private final BlockingQueue<Map.Entry<ComputationId, ToolResult>> completed =
      new ArrayBlockingQueue<>(4);
  private final ExecutorService watchers = Executors.newSingleThreadExecutor();
  private final FakeContext context = new FakeContext();

  private final Tool<LongJob.Job> tool =
      LongJob.tool(
          runner,
          (id, result) -> completed.add(new AbstractMap.SimpleImmutableEntry<>(id, result)),
          watchers);

  @AfterEach
  void stopTheWatchers() {
    watchers.shutdownNow();
  }

  private Map.Entry<ComputationId, ToolResult> awaitCompletion() throws InterruptedException {
    Map.Entry<ComputationId, ToolResult> entry = completed.poll(30, TimeUnit.SECONDS);
    if (entry == null) {
      throw new AssertionError("the watcher never completed the computation");
    }
    return entry;
  }

  @Test
  void returns_deferred_while_the_process_is_still_running() {
    Awaited<ToolResult> awaited = tool.execute(new LongJob.Job(), context);

    assertThat(awaited).isInstanceOf(Awaited.Deferred.class);
    assertThat(completed).isEmpty();
    assertThat(context.defers()).isEqualTo(1);
  }

  @Test
  void takes_its_id_before_it_starts_anything() {
    tool.execute(new LongJob.Job(), context);

    // The id existed — and the phase already named the wait — before any thread that could answer
    // it had been handed anything. Ordered by construction, not by being fast enough.
    assertThat(context.defers()).isEqualTo(1);
    assertThat(completed).isEmpty();
  }

  @Test
  void completes_with_the_very_id_it_handed_out_when_the_process_finishes()
      throws InterruptedException {
    tool.execute(new LongJob.Job(), context);
    runner.release();

    Map.Entry<ComputationId, ToolResult> entry = awaitCompletion();

    assertThat(entry.getKey()).isEqualTo(FakeContext.DEFERRED);
    assertThat(entry.getValue().isError()).isFalse();
    assertThat(entry.getValue().content()).contains("fstrim -av").contains("/: 12 GiB");
  }

  @Test
  void completes_with_an_error_result_when_the_process_fails() throws InterruptedException {
    FakeRunner failing =
        new FakeRunner().answering("fstrim", new CommandRunner.Output(1, "", "not supported"));
    Tool<LongJob.Job> failingTool =
        LongJob.tool(
            failing,
            (id, result) -> completed.add(new AbstractMap.SimpleImmutableEntry<>(id, result)),
            watchers);

    failingTool.execute(new LongJob.Job(), context);
    Map.Entry<ComputationId, ToolResult> entry = awaitCompletion();

    assertThat(entry.getValue().isError()).isTrue();
    assertThat(entry.getValue().content()).contains("exit 1").contains("not supported");
  }

  /**
   * The orphan path (final review, finding #3). A deferred computation is answered once, by whoever
   * holds its id — and the watcher thread is the only thing that holds it. If it dies with an
   * exception the call waits FOREVER: {@code Phase.AwaitingTools#outstanding} contributes no effect
   * for {@code AwaitingResult}, so nothing re-fires and no staleness sweep re-asks. On a box doing
   * rounds every half hour that means the rounds simply stop.
   */
  @Nested
  class When_the_watcher_throws {

    private final FakeRunner exploding =
        new FakeRunner() {
          @Override
          public Output run(List<String> argv) {
            throw new IllegalStateException("the host went away");
          }
        };

    @Test
    void the_computation_is_completed_with_an_error_rather_than_orphaned()
        throws InterruptedException {
      Tool<LongJob.Job> tool = LongJob.tool(exploding, LongJobTest.this::record, watchers);

      tool.execute(new LongJob.Job(), context);
      Map.Entry<ComputationId, ToolResult> entry = awaitCompletion();

      assertThat(entry.getKey()).isEqualTo(FakeContext.DEFERRED);
      assertThat(entry.getValue().isError()).isTrue();
      assertThat(entry.getValue().content()).contains("the host went away");
    }

    /**
     * The realistic way in: the completion door itself fails, because the context closed while a
     * trim was still running. There is genuinely nothing left to answer with, so the only
     * requirement is that the watcher does not die screaming into an executor nobody is watching.
     */
    @Test
    void a_completion_door_that_also_fails_is_survived_quietly() throws InterruptedException {
      // The job itself SUCCEEDS here, so the first completion attempt is the real one — and it is
      // the desk that is gone. That is the shutdown-mid-trim shape: two attempts, the result and
      // then the report of the failure to deliver it, and neither escapes the watcher.
      CountDownLatch attempted = new CountDownLatch(2);
      Tool<LongJob.Job> tool =
          LongJob.tool(
              new FakeRunner().answering("fstrim", "/: 12 GiB"),
              (id, result) -> {
                attempted.countDown();
                throw new IllegalStateException("the desk is closed too");
              },
              watchers);

      tool.execute(new LongJob.Job(), context);

      assertThat(attempted.await(30, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void record(ComputationId id, ToolResult result) {
    completed.add(new AbstractMap.SimpleImmutableEntry<>(id, result));
  }

  @Test
  void expects_its_answer_to_outlive_the_process_that_asked() {
    assertThat(tool.requiredCompletion()).isEqualTo(CompletionPolicy.DURABLE);
  }

  @Test
  void gives_the_calling_thread_back_immediately() {
    long before = System.nanoTime();

    Awaited<ToolResult> awaited = tool.execute(new LongJob.Job(), context);

    // The runner is blocked and stays blocked; if the tool had run it inline on this thread, this
    // call would not have returned at all.
    assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(5));
    assertThat(awaited).isInstanceOf(Awaited.Deferred.class);
    assertThat(completed).isEmpty();
  }
}
