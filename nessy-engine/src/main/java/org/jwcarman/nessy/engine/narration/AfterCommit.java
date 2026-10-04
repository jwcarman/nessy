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
package org.jwcarman.nessy.engine.narration;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.backend.lock.Locks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells an agent's listeners about what a locked step wrote only once that step has committed, and
 * never if it rolled back.
 *
 * <p><b>Why.</b> A step appends events inside {@link Locks#withLock}, which on a database is one
 * transaction. A listener told inside it could hear about something a rollback then undid, and a
 * listener that reads storage could not yet see what it was told about.
 *
 * <p><b>How.</b> {@link #locked} runs a step. Under the lock, before any work, the step reserves
 * its place in this agent's delivery: a {@link Step}, which holds what the step narrates, in order.
 * When {@code withLock} returns -- the work finished and the commit succeeded -- the step is
 * released and its narrations are delivered. When it throws, the step is cancelled and they are
 * never delivered. The reservation is made under the lock, so the order of two steps of one agent
 * is the order they committed in, whichever thread is slower to get back from its commit.
 *
 * <p><b>Order is per agent.</b> Each agent has its own line. Delivery for an agent waits at a step
 * that is not yet resolved, and what was queued behind it waits its turn; another agent's line is
 * never held up by it. Narration produced outside any step -- a streamed delta, a note on a call in
 * flight -- joins the same line behind whatever was reserved before it, and is delivered at once
 * when nothing is ahead of it.
 *
 * <p><b>Nothing here blocks.</b> A step's thread never waits on a listener, or on another step:
 * delivery is handed to the narrator behind this, which tells its listeners on threads of its own.
 * A step released after another is delivered by whichever thread resolves the one ahead of it.
 *
 * <p><b>A backstop, not a design.</b> A step is released or cancelled on every path out of {@link
 * #locked}. Should a defect leave one unresolved, it is dropped after the bound and logged at
 * ERROR, so one agent's narration cannot stop forever.
 */
public final class AfterCommit implements Narrator, AutoCloseable {

  /** How long a step may stay unresolved before it is dropped. */
  public static final Duration DEFAULT_BOUND = Duration.ofSeconds(30);

  private static final Logger LOG = LoggerFactory.getLogger(AfterCommit.class);

  private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

  private final Narrator delegate;
  private final Duration bound;
  private final ConcurrentHashMap<Key, Line> lines = new ConcurrentHashMap<>();
  private final ScheduledThreadPoolExecutor watchdog = watchdog();

  public AfterCommit(Narrator delegate) {
    this(delegate, DEFAULT_BOUND);
  }

  AfterCommit(Narrator delegate, Duration bound) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.bound = Objects.requireNonNull(bound, "bound must not be null");
  }

  private static ScheduledThreadPoolExecutor watchdog() {
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("nessy-narration-watchdog").factory());
    executor.setRemoveOnCancelPolicy(true);
    // No thread unless a step is outstanding.
    executor.setKeepAliveTime(1, TimeUnit.SECONDS);
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  /**
   * Runs one locked step, delivering what it narrates after it commits.
   *
   * <p>The step handed to {@code work} is reserved under the lock, as the first thing done in it.
   * It is released only if {@code withLock} returned normally, whatever the work caught along the
   * way, and cancelled on any other way out.
   */
  public <T> T locked(Locks locks, AgentType type, AgentId agent, Function<Step, T> work) {
    Step[] reserved = new Step[1];
    boolean committed = false;
    try {
      T result =
          locks.withLock(
              Locks.TURN,
              type,
              agent,
              () -> {
                reserved[0] = reserve(type, agent);
                return work.apply(reserved[0]);
              });
      committed = true;
      return result;
    } finally {
      if (reserved[0] != null) {
        if (committed) {
          reserved[0].release();
        } else {
          reserved[0].cancel();
        }
      }
    }
  }

  /** Narration from outside a locked step: behind anything reserved before it, else at once. */
  @Override
  public void narrate(Narrated narrated) {
    Step ready = new Step(new Key(narrated.agentType(), narrated.agentId()), State.RELEASED);
    ready.hold(narrated);
    enqueue(ready);
    drain(ready.line);
  }

  @Override
  public boolean listening() {
    return delegate.listening();
  }

  /** Stops the backstop. What was handed on before this is still told. */
  @Override
  public void close() {
    watchdog.shutdownNow();
  }

  /** Called with the lock held, so the place in line is the order the steps commit in. */
  Step reserve(AgentType type, AgentId agent) {
    Step step = new Step(new Key(type, agent), State.OPEN);
    enqueue(step);
    try {
      step.watch.set(watchdog.schedule(step::expire, bound.toNanos(), TimeUnit.NANOSECONDS));
    } catch (RuntimeException e) {
      // Already in line: a step that cannot be watched must not stay there, or it holds back
      // every narration of its agent for as long as the process runs.
      step.cancel();
      throw e;
    }
    return step;
  }

  private void enqueue(Step step) {
    while (true) {
      Line line = lines.computeIfAbsent(step.key, Line::new);
      synchronized (line.monitor) {
        if (!line.retired) {
          step.line = line;
          line.queue.addLast(step);
          return;
        }
      }
    }
  }

  /** Tells, in order, everything at the front of the line that is settled. One thread at a time. */
  private void drain(Line line) {
    synchronized (line.monitor) {
      if (line.draining) {
        return;
      }
      line.draining = true;
    }
    boolean finished = false;
    try {
      while (true) {
        List<Step> settled = new ArrayList<>();
        synchronized (line.monitor) {
          while (!line.queue.isEmpty() && line.queue.peekFirst().state != State.OPEN) {
            settled.add(line.queue.pollFirst());
          }
          if (settled.isEmpty()) {
            line.draining = false;
            if (line.queue.isEmpty()) {
              line.retired = true;
              lines.remove(line.key, line);
            }
            finished = true;
            return;
          }
        }
        settled.forEach(this::deliver);
      }
    } finally {
      // Something other than a RuntimeException escaped a delivery. The line must not stay
      // marked as being drained, or nothing for this agent would ever be told again.
      if (!finished) {
        synchronized (line.monitor) {
          line.draining = false;
        }
      }
    }
  }

  private void deliver(Step step) {
    if (step.state != State.RELEASED) {
      return;
    }
    for (Held held : step.held) {
      try {
        held.snapshot().wrap(() -> delegate.narrate(held.narrated())).run();
      } catch (RuntimeException e) {
        LOG.warn(
            "[{}] agent {}: a narration of {} could not be handed on; carrying on",
            step.key.type().value(),
            step.key.agent().value(),
            held.narrated().event().getClass().getSimpleName(),
            e);
      }
    }
  }

  private enum State {
    OPEN,
    RELEASED,
    CANCELLED
  }

  private record Key(AgentType type, AgentId agent) {}

  private record Held(Narrated narrated, ContextSnapshot snapshot) {}

  private static final class Line {
    /** The one lock of this line: guards the queue, draining, retired and its steps' states. */
    private final Object monitor = new Object();

    private final Key key;
    private final ArrayDeque<Step> queue = new ArrayDeque<>();
    private boolean draining;
    private boolean retired;

    Line(Key key) {
      this.key = key;
    }
  }

  /**
   * One locked step's place in its agent's line, and what the step narrated while it held it.
   *
   * <p>The thread running the step calls {@link #narrate} while the lock is held. Releasing and
   * cancelling belong to {@link #locked}.
   */
  public final class Step {

    private final Key key;
    private final List<Held> held = new ArrayList<>();
    private volatile State state;
    private final AtomicReference<ScheduledFuture<?>> watch = new AtomicReference<>();
    private Line line;

    private Step(Key key, State state) {
      this.key = key;
      this.state = state;
    }

    /** A live signal, held until the step commits; never told if it does not. */
    public void narrate(Narration.Live event) {
      hold(Narrated.live(key.type(), key.agent(), event));
    }

    /**
     * A story event the step wrote, at the {@code seq} it was written and the {@code at} its batch
     * was appended with; held until the step commits, never told if it does not.
     */
    public void narrate(Narration.Story event, Seq seq, Instant at) {
      hold(Narrated.story(key.type(), key.agent(), event, seq, at));
    }

    private void hold(Narrated narrated) {
      held.add(new Held(narrated, SNAPSHOTS.captureAll()));
    }

    void release() {
      resolve(State.RELEASED);
    }

    void cancel() {
      resolve(State.CANCELLED);
    }

    private void expire() {
      int dropped = held.size();
      if (resolve(State.CANCELLED)) {
        LOG.error(
            "[{}] agent {}: a narration placeholder was still unresolved after {}; dropped, and"
                + " its {} narration(s) will not be told",
            key.type().value(),
            key.agent().value(),
            bound,
            dropped);
      }
    }

    private boolean resolve(State to) {
      boolean changed;
      synchronized (line.monitor) {
        changed = state == State.OPEN;
        if (changed) {
          state = to;
        }
      }
      if (changed) {
        ScheduledFuture<?> pending = watch.get();
        if (pending != null) {
          pending.cancel(false);
        }
        drain(line);
      }
      return changed;
    }
  }
}
