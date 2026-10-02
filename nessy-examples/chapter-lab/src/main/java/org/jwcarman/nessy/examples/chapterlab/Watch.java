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
package org.jwcarman.nessy.examples.chapterlab;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.backend.chapter.Chapters;

/**
 * Watches the engine's chapter keeper from outside so the lab can wait for it.
 *
 * <p>The keeper works off the agent's thread, and while it holds the agent's lease a later turn's
 * end does nothing. A lab that drove every turn at once would lose cuts to that, and the chapters
 * would depend on timing. So after each turn the lab waits here until the policy has been asked
 * about that turn, the chapters it named are stored, and every closed chapter has its summary.
 *
 * <p>A summary or a cut that fails after its attempts stops the run with the reason: the lab never
 * scores a context built from chapters that quietly were not written.
 */
final class Watch {

  private static final Duration PATIENCE = Duration.ofMinutes(10);
  private static final Duration POLL = Duration.ofMillis(20);

  private final PrintStream out;
  private final Duration pause;
  private final AtomicLong askedThrough = new AtomicLong();
  private final AtomicLong closeThrough = new AtomicLong();
  private final AtomicInteger written = new AtomicInteger();
  private final AtomicReference<String> failure = new AtomicReference<>();

  Watch(PrintStream out, Duration pause) {
    this.out = Objects.requireNonNull(out, "out must not be null");
    this.pause = Objects.requireNonNull(pause, "pause must not be null");
  }

  /** The policy, noting what it was asked about and what it answered. */
  ChapterPolicy watching(ChapterPolicy policy) {
    return open -> {
      List<TurnId> ends;
      try {
        ends = policy.ends(open);
      } catch (RuntimeException failed) {
        failure.compareAndSet(null, "the chapter policy failed: " + failed.getMessage());
        throw failed;
      }
      ends.forEach(end -> closeThrough.accumulateAndGet(end.value(), Math::max));
      askedThrough.accumulateAndGet(open.turns().getLast().value(), Math::max);
      return ends;
    };
  }

  /**
   * The summariser, trying a failed summary again, saying so when a chapter is written, and
   * stopping the run when it cannot be.
   */
  Summarizer reporting(Summarizer summarizer) {
    return chapter -> {
      try {
        String text =
            Models.retrying(
                () -> {
                  String written = summarizer.summarize(chapter);
                  if (written == null || written.isBlank()) {
                    throw new IllegalStateException("the model wrote nothing");
                  }
                  return written;
                },
                pause);
        out.println(
            "chapter %d summarised: turns %s through %s, %d words"
                .formatted(
                    written.incrementAndGet(),
                    chapter.from().value(),
                    chapter.through().value(),
                    text.strip().split("\\s+").length));
        return text;
      } catch (RuntimeException failed) {
        failure.compareAndSet(
            null,
            "the summary of turns %s through %s failed: %s"
                .formatted(chapter.from().value(), chapter.through().value(), failed.getMessage()));
        throw failed;
      }
    };
  }

  /**
   * Waits until the keeper has finished with {@code turn}.
   *
   * @throws IllegalStateException if a policy or a summary failed, or the keeper did not finish in
   *     ten minutes
   */
  void settle(Chapters chapters, AgentType type, AgentId agent, TurnId turn) {
    Instant deadline = Instant.now().plus(PATIENCE);
    while (!settled(chapters, type, agent, turn)) {
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException(
            "the chapters had not settled after %s: the policy was asked through turn %d and"
                    .formatted(PATIENCE, askedThrough.get())
                + " the last turn was "
                + turn.value());
      }
      try {
        Thread.sleep(POLL);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for the chapters");
      }
    }
  }

  private boolean settled(Chapters chapters, AgentType type, AgentId agent, TurnId turn) {
    String failed = failure.get();
    if (failed != null) {
      throw new IllegalStateException(failed);
    }
    long due = closeThrough.get();
    boolean stored =
        due == 0 || chapters.closedThrough(type, agent).map(t -> t.value() >= due).orElse(false);
    return askedThrough.get() >= turn.value()
        && stored
        && chapters.unsummarized(type, agent).isEmpty();
  }
}
