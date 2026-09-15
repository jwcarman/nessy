package org.jwcarman.nessy.memory.summarizing;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs summarisers' sweeps every so often, on one virtual thread, until closed. */
public final class Sweeper implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Sweeper.class);

  private final List<HeadSummarizer> summarizers;
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("nessy-sweep").factory());

  public Sweeper(List<HeadSummarizer> summarizers, Duration interval) {
    this.summarizers =
        List.copyOf(Objects.requireNonNull(summarizers, "summarizers must not be null"));
    Objects.requireNonNull(interval, "interval must not be null");
    timer.scheduleWithFixedDelay(
        this::sweep, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void sweep() {
    for (HeadSummarizer summarizer : summarizers) {
      try {
        summarizer.sweep();
      } catch (RuntimeException e) {
        // One bad sweep must not cancel the schedule: the next one runs regardless.
        LOG.warn("a summary sweep failed; the next one will try again", e);
      }
    }
  }

  @Override
  public void close() {
    timer.shutdownNow();
  }
}
