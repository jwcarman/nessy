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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Harness}'s harness-internal per-agent-id subscriber registry (front-ends spec §2): {@link
 * Harness#subscribe(AgentId, TurnObserver)} — reached from {@link Agent#subscribe(TurnObserver)} —
 * adds a routing entry here; {@link #observerFor(AgentId)} is what {@link Harness} hands its model-
 * and tool-call executor factories in place of the raw configured {@code TurnObserver}, so BOTH the
 * synchronous {@code tell}/{@code drive} path and the {@code DeliveryWorker} fold path narrate
 * through the same fanout for a given id.
 *
 * <p>One {@link CopyOnWriteArrayList} per id, in a {@link ConcurrentHashMap} keyed by id — cheap
 * under this workload's read-heavy, write-rare shape (subscribe/close are occasional; emit is every
 * event of every turn) and its snapshot-iterator semantics hand {@link #emit} both properties the
 * brief's risk list names for free: emission order matches subscribe order (insertion order, held
 * in the snapshot array an iterator was handed), and a {@code close()} racing an in-flight {@link
 * #emit} is safe without synchronization — closing mutates a fresh backing array; the iterator
 * already in hand keeps the one it started with, so it neither throws nor sees the removal
 * mid-sweep. Two ids never cross because {@link #emit} only ever reads the one list {@code
 * subscribers.get(id)} names.
 *
 * <p>Two audiences, two throw postures, both preserved from before this class existed: {@code
 * global} — the harness's configured {@code TurnObserver} ({@link
 * org.jwcarman.nessy.agent.host.HarnessConfig#turnObserver}), composed here as one more subscriber
 * per the spec — runs first and UNGUARDED, so a throw there keeps its long-standing meaning ({@link
 * TurnObserver}'s own javadoc: a throwing observer aborts the model-path call it narrates,
 * attributed to the caller's own {@code tell}). Every {@code subscribe}d observer runs after it,
 * individually isolated in a try/catch: a throw there is logged and dropped, never allowed to
 * poison the fold for the other subscribers or escape onto whatever thread narrated it — a worker's
 * heartbeat included.
 */
final class TurnFanout {

  private static final Logger log = LoggerFactory.getLogger(TurnFanout.class);

  private final TurnObserver global;
  private final ConcurrentMap<AgentId, CopyOnWriteArrayList<TurnObserver>> subscribers =
      new ConcurrentHashMap<>();

  TurnFanout(TurnObserver global) {
    this.global = Objects.requireNonNull(global, "global must not be null");
  }

  /**
   * Adds {@code observer} to {@code id}'s routing list and hands back the {@link Subscription} that
   * removes exactly this registration. Thread-safe under concurrent subscribe/close/emit for the
   * same or different ids (see class javadoc).
   */
  Subscription subscribe(AgentId id, TurnObserver observer) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(observer, "observer must not be null");
    CopyOnWriteArrayList<TurnObserver> perId =
        subscribers.computeIfAbsent(id, unused -> new CopyOnWriteArrayList<>());
    perId.add(observer);
    return new Registration(perId, observer);
  }

  /**
   * The {@link TurnObserver} {@link Harness} threads through its model- and tool-call executor
   * factories for {@code id}: every event either executor narrates arrives here and fans out to
   * {@code global} plus {@code id}'s current subscribers, whichever call — synchronous or a later
   * worker-driven fold — happens to be narrating it.
   */
  TurnObserver observerFor(AgentId id) {
    return event -> emit(id, event);
  }

  private void emit(AgentId id, TurnEvent event) {
    global.on(event);
    CopyOnWriteArrayList<TurnObserver> perId = subscribers.get(id);
    if (perId == null) {
      return;
    }
    for (TurnObserver subscriber : perId) {
      try {
        subscriber.on(event);
      } catch (RuntimeException e) {
        log.warn(
            "a subscriber for agent {} threw handling {}; isolated, the fold continues",
            id.value(),
            event.getClass().getSimpleName(),
            e);
      }
    }
  }

  /** Idempotent via {@link AtomicBoolean}: a second {@link #close()} is a silent no-op. */
  private static final class Registration implements Subscription {

    private final CopyOnWriteArrayList<TurnObserver> perId;
    private final TurnObserver observer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Registration(CopyOnWriteArrayList<TurnObserver> perId, TurnObserver observer) {
      this.perId = perId;
      this.observer = observer;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        perId.remove(observer);
      }
    }
  }
}
