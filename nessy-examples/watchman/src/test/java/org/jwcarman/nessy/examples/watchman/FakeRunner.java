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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A host that never existed: canned output per command, and a record of everything asked of it.
 *
 * <p>This is the whole reason {@link CommandRunner} is an interface. Spec §4: "No test shells out
 * to the host."
 *
 * <p>Not {@code final}, so a test that needs a host which FAILS rather than answers can override
 * {@link #run(List)} — see {@code LongJobTest.When_the_watcher_throws}.
 */
class FakeRunner implements CommandRunner {

  private final Map<String, Output> canned = new LinkedHashMap<>();
  private final List<List<String>> asked = new ArrayList<>();
  private final List<Duration> timeouts = new ArrayList<>();
  private Output fallback = new Output(127, "", "command not found");
  private CountDownLatch gate;

  /** Answers {@code output} whenever the command's first word is {@code command}. */
  FakeRunner answering(String command, Output output) {
    canned.put(command, output);
    return this;
  }

  /** Answers {@code stdout} with exit 0 whenever the command's first word is {@code command}. */
  FakeRunner answering(String command, String stdout) {
    return answering(command, new Output(0, stdout, ""));
  }

  /** Answers {@code output} for anything not otherwise cannned. */
  FakeRunner otherwise(Output output) {
    this.fallback = output;
    return this;
  }

  /** Makes every call block until {@link #release()} — the "still running" half of a long job. */
  FakeRunner blocking() {
    this.gate = new CountDownLatch(1);
    return this;
  }

  /** Lets a {@link #blocking()} runner finish. */
  void release() {
    gate.countDown();
  }

  /** Every argv this runner was asked for, in order. */
  List<List<String>> asked() {
    return List.copyOf(asked);
  }

  /** The single argv this runner was asked for. */
  List<String> onlyAsked() {
    if (asked.size() != 1) {
      throw new IllegalStateException("expected exactly one command, got " + asked);
    }
    return asked.getFirst();
  }

  @Override
  public Output run(List<String> argv) {
    asked.add(List.copyOf(argv));
    if (gate != null) {
      try {
        gate.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Output(-1, "", "interrupted");
      }
    }
    return canned.getOrDefault(argv.getFirst(), fallback);
  }

  /**
   * Records the deadline a caller asked for, so a test can assert that {@code apply_updates} really
   * does get minutes rather than the runner's thirty-second default.
   */
  @Override
  public Output run(List<String> argv, Duration timeout) {
    timeouts.add(timeout);
    return run(argv);
  }

  /** The per-call deadlines this runner was given, in order. Empty means nobody asked for one. */
  List<Duration> timeouts() {
    return List.copyOf(timeouts);
  }
}
