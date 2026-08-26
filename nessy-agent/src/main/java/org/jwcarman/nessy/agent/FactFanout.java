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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.api.turn.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Harness}'s one fact stream (agentic-o11y spec §3) — {@link TurnFanout}'s sibling, for the
 * other half of the event model. Where {@code TurnFanout} carries {@code TurnEvent} texture per
 * agent id, this carries the FOLD'S OUTPUT for the whole harness: both fold sites — {@link
 * DefaultAgent}'s synchronous shell and {@link DeliveryWorker}'s durable one — publish {@code
 * (agentId, event, transition)} here, and everything interested subscribes.
 *
 * <p>Not keyed by id, unlike {@code TurnFanout}: an {@link HarnessObserver} is a harness-level
 * subscriber now, told which scope each fact belongs to by the leading {@link AgentId} parameter
 * its methods carry. One {@link CopyOnWriteArrayList} is therefore the whole registry, and it buys
 * the same two properties {@code TurnFanout} relies on: emission order matches subscribe order, and
 * a {@code close()} racing an in-flight publish is safe without synchronization — closing mutates a
 * fresh backing array while the iterator already in hand keeps the one it started with.
 *
 * <p><b>No cross-publish ordering guarantee per agent id.</b> Each fold site publishes AFTER its
 * CAS lands, not under it, so two folds racing on ONE scope can reach subscribers in either order —
 * the second writer to commit is not necessarily the second to publish. A subscriber holding
 * per-scope state must therefore tolerate a close arriving before its open (and the reverse), and
 * must not treat the sequence it sees as the order the store committed. What IS guaranteed is that
 * every published fact was committed: the stream carries the fold's output, never its input.
 *
 * <p><b>Every subscriber is isolated</b> (spec §3): a throw is logged and dropped, never propagated
 * into the fold. This is stricter than {@code TurnFanout}'s posture, which lets the harness's one
 * configured global {@code TurnObserver} throw through by long-standing contract. Nothing of the
 * sort applies here — the fold has already committed by the time a fact is published, so letting a
 * narrator's exception escape would corrupt an outcome that is already a fact in the store, and one
 * bad subscriber must never starve the observability bridge (or any other) of the facts it needs to
 * close an open span.
 */
final class FactFanout {

  private static final Logger log = LoggerFactory.getLogger(FactFanout.class);

  private final CopyOnWriteArrayList<HarnessObserver> subscribers = new CopyOnWriteArrayList<>();

  /**
   * Adds {@code observer} to the stream and hands back the {@link Subscription} that removes
   * exactly this registration.
   */
  Subscription subscribe(HarnessObserver observer) {
    Objects.requireNonNull(observer, "observer must not be null");
    subscribers.add(observer);
    return new Registration(observer);
  }

  /** Test seam: how many subscribers are live on this stream right now. */
  int subscriberCount() {
    return subscribers.size();
  }

  void applied(AgentId id, AgentEvent event, Transition transition) {
    publish(id, "applied", observer -> observer.applied(id, event, transition));
  }

  void ignored(AgentId id, AgentEvent event) {
    publish(id, "ignored", observer -> observer.ignored(id, event));
  }

  void renderFailed(AgentId id, Object observation, RuntimeException error) {
    publish(id, "renderFailed", observer -> observer.renderFailed(id, observation, error));
  }

  void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
    publish(id, "applyFailed", observer -> observer.applyFailed(id, event, error));
  }

  void reFired(AgentId id, List<Effect> effects) {
    publish(id, "reFired", observer -> observer.reFired(id, effects));
  }

  void observationRequeued(AgentId id, Object observation) {
    publish(id, "observationRequeued", observer -> observer.observationRequeued(id, observation));
  }

  /**
   * One fact to every subscriber, each individually guarded. {@code moment} names which stream
   * method threw, for a log line someone can actually chase.
   */
  private void publish(AgentId id, String moment, Consumer<HarnessObserver> delivery) {
    for (HarnessObserver subscriber : subscribers) {
      try {
        delivery.accept(subscriber);
      } catch (RuntimeException e) {
        log.warn(
            "a fact subscriber for agent {} threw handling {}; isolated, the fold continues",
            id.value(),
            moment,
            e);
      }
    }
  }

  /** Idempotent via {@link AtomicBoolean}: a second {@link #close()} is a silent no-op. */
  private final class Registration implements Subscription {

    private final HarnessObserver observer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Registration(HarnessObserver observer) {
      this.observer = observer;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        subscribers.remove(observer);
      }
    }
  }
}
