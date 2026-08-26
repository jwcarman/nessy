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
package org.jwcarman.nessy.agent.spi;

import java.util.List;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentTransition;
import org.jwcarman.nessy.agent.Effect;

/**
 * Machine-level narration: exactly what the shell decided, including the next phase. Observers
 * narrate; they never influence (§8).
 *
 * <p><b>It observes the HARNESS, not an agent</b> (agentic-o11y spec §3, named by James
 * 2026-08-26). There is one fact stream per harness and the fold is its producer: both fold sites —
 * the synchronous shell ({@code DefaultAgent}) and the durable one ({@code DeliveryWorker}) —
 * publish through one door, and everything interested subscribes to it via {@code
 * Harness.subscribe}. Every method here therefore leads with the {@link AgentId} the fact is about:
 * the events are not specific to any one agent, they name which agent they concern. One observer
 * instance serves every scope, where the retired per-scope {@code AgentObserver} was stamped fresh
 * per id by a factory.
 *
 * <p>The stream carries the fold's OUTPUT, not its input: an event is not a fact until the reducer
 * accepts it, so a dropped delivery arrives as {@link #ignored} and changed nothing. {@code
 * TurnEvent} deliberately stays outside this stream — deltas and thinking chunks are an executor
 * narrating inside an in-flight effect, before any fact exists, and remain {@code TurnObserver}'s.
 *
 * <p>Subscribers are isolated: a throw is logged and dropped, never propagated into the fold.
 *
 * <p><b>Publishes for one scope are not guaranteed to arrive in commit order.</b> Each fold site
 * publishes after its CAS, not under it, so two concurrent folds on a single {@link AgentId} can
 * reach an observer in either order. An implementation holding per-scope state must tolerate a
 * close before its open — treat every transition as idempotent and every unmatched close as a
 * no-op. Every fact you are handed did commit; the order in which you are handed them is not the
 * order the store recorded them.
 */
public interface HarnessObserver {

  /** One event applied: the fact and the whole transition — next phase, commits, effects. */
  void applied(AgentId id, AgentEvent event, AgentTransition transition);

  /** A stale or duplicate completion, discarded before anything was written (§3.4). */
  void ignored(AgentId id, AgentEvent event);

  /** A renderer threw; the observation is discarded and the scope stays idle (§3.7). */
  void renderFailed(AgentId id, Object observation, RuntimeException error);

  /**
   * Applying a completion threw — a malformed delivery or a phase-contract violation. The event is
   * dropped and narrated; the scope's phase is unchanged (validation belongs at the executor seam).
   */
  void applyFailed(AgentId id, AgentEvent event, RuntimeException error);

  /** The recovery arm re-dispatched a stalled phase's outstanding effects (§6.1). */
  void reFired(AgentId id, List<Effect> effects);

  /** An observation lost the idle race and went back to the backlog (§3.3). */
  void observationRequeued(AgentId id, Object observation);

  /** Accepts everything, tells no one. */
  static HarnessObserver noop() {
    return new HarnessObserver() {
      @Override
      public void applied(AgentId id, AgentEvent event, AgentTransition transition) {
        // deliberately silent: the noop observer narrates nothing
      }

      @Override
      public void ignored(AgentId id, AgentEvent event) {
        // deliberately silent: the noop observer narrates nothing
      }

      @Override
      public void renderFailed(AgentId id, Object observation, RuntimeException error) {
        // deliberately silent: the noop observer narrates nothing
      }

      @Override
      public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
        // deliberately silent: the noop observer narrates nothing
      }

      @Override
      public void reFired(AgentId id, List<Effect> effects) {
        // deliberately silent: the noop observer narrates nothing
      }

      @Override
      public void observationRequeued(AgentId id, Object observation) {
        // deliberately silent: the noop observer narrates nothing
      }
    };
  }
}
