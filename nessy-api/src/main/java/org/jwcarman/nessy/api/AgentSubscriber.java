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
 * <p><b>A subscriber that throws is ejected, uniformly.</b> Narration is at-least-once and never
 * transactional with the record it is describing — nothing a subscriber does can roll back a model
 * call or a tool call that already happened. So a throw is logged and the subscriber dropped from
 * that point on, whether it happened on the model path or a tool path, and the turn continues
 * either way. Anything else would hand a subscriber a veto over the agent it is only supposed to be
 * watching: a broken or hostile watcher on the model path could abort every call it saw, which is a
 * denial-of-service surface reachable from a UI.
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
