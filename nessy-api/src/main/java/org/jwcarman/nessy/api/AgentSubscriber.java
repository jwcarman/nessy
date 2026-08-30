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
package org.jwcarman.nessy.api;

/**
 * Whoever is sitting there, watching an agent work — a REPL painting deltas, a UI narrating
 * progress. The audience may not exist at all: an unattended agent runs every turn against {@link
 * #noop()} and loses nothing.
 *
 * <p>A single method, so a watcher with one concern is a lambda. A watcher with several switches
 * over {@link AgentEvent}.
 *
 * <p><b>Throw semantics are asymmetric, deliberately.</b> A subscriber that throws while narrating
 * the model path aborts the call it is narrating — the subscriber is the caller's own code, so its
 * exception is the caller's exception. A subscriber that throws while narrating a tool is logged
 * and dropped instead: letting it propagate would misattribute a bug in the UI to the tool, killing
 * a call that was otherwise succeeding.
 *
 * <p><b>Threading:</b> events arrive on whatever thread produced them, which need not be the thread
 * that drove the turn. A subscriber that only appends to a buffer it owns exclusively is fine
 * either way; one that accumulates across events must make itself thread-safe.
 */
@FunctionalInterface
public interface AgentSubscriber {

  void on(AgentEvent event);

  /**
   * Composes a subscriber from per-variant consumers — the middle rung between a lambda and {@link
   * AgentSubscriberAdapter}. Variants never registered stay silent.
   */
  static AgentSubscriber of(java.util.function.Consumer<AgentSubscriberConfig> customizer) {
    java.util.Objects.requireNonNull(customizer, "customizer must not be null");
    AgentSubscriberConfig config = new AgentSubscriberConfig();
    customizer.accept(config);
    return config.build();
  }

  /** The absent audience: accepts everything, tells no one. */
  static AgentSubscriber noop() {
    return event -> {};
  }
}
