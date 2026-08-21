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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.store.StaleStateException;
import org.jwcarman.nessy.api.message.ContentBlock;

/**
 * The shell: load–handle–save–dispatch with a retry (§3.4). No concurrency machinery — the store's
 * version CAS is the only lock (§3.2), and executors deliver on their own stacks (§4).
 */
public final class DefaultAgent<O> implements Agent<O> {

  private final AgentWiring<O> wiring;

  public DefaultAgent(AgentWiring<O> wiring) {
    this.wiring = Objects.requireNonNull(wiring, "wiring must not be null");
  }

  @Override
  public void observe(O observation) {
    wiring.backlog().add(observation);
    drive();
  }

  @Override
  public void drive() {
    State state = wiring.store().load();
    if (state.phase() instanceof Phase.Idle) {
      drain();
      return;
    }
    if (isStale()) {
      List<Effect> outstanding = state.phase().outstandingEffects();
      wiring.observer().reFired(outstanding);
      outstanding.forEach(this::dispatch); // §6.1 — the re-fire arm
    }
  }

  /**
   * The continuation door: executors are handed this method reference as their Sink at dispatch
   * (§4). Completions that lose the version race re-handle against fresh state until applied or
   * ignored (§3.4).
   */
  void deliver(AgentEvent event) {
    while (true) {
      try {
        applyOnce(event);
        return;
      } catch (StaleStateException e) {
        // another writer advanced the scope — re-handle against what it left behind
      } catch (RuntimeException e) {
        wiring
            .observer()
            .applyFailed(event, e); // narrate-and-drop: the shell manufactures no events
        return;
      }
    }
  }

  private void applyOnce(AgentEvent event) {
    applyOnce(wiring.store().load(), event);
  }

  private void applyOnce(State state, AgentEvent event) {
    Transition t = state.phase().handle(event); // decide before committing
    if (t.isIgnored()) {
      wiring.observer().ignored(event);
      return;
    }
    t.commit().forEach(wiring.memory()::remember); // commit before dispatch
    wiring.store().save(new State(t.next(), state.version()));
    wiring.observer().applied(event, t);
    t.effects().forEach(this::dispatch);
    if (t.next() instanceof Phase.Idle && wiring.drainOnIdle()) {
      drive(); // §3.1 — the autonomous wiring's drain executor
    }
  }

  private void drain() {
    while (true) {
      State state = wiring.store().load();
      if (!(state.phase() instanceof Phase.Idle)) {
        return;
      }
      Optional<O> next = wiring.backlog().poll();
      if (next.isEmpty()) {
        return;
      }
      O observation = next.get();
      List<ContentBlock> content;
      try {
        content = wiring.renderer().render(observation);
      } catch (RuntimeException e) {
        wiring.observer().renderFailed(observation, e); // discard; stay idle; keep draining
        continue;
      }
      if (content.isEmpty()) {
        continue; // an empty render is a decline — skip, keep draining (§3.7)
      }
      try {
        applyOnce(state, new AgentEvent.Observed(content));
      } catch (StaleStateException e) {
        wiring.backlog().add(observation); // lost race → back to the backlog (§3.3)
        wiring.observer().observationRequeued(observation);
      }
    }
  }

  /**
   * The redrive door (spec §4.3 amendment): unblocks gated tool calls by re-dispatching this
   * scope's outstanding {@link Effect.ExecuteTool} effects unconditionally — a decided approval is
   * not gated by staleness. At-least-once, same semantics as the §6.1 recovery arm; ToolCallId
   * dedup absorbs any duplicate completion.
   *
   * <p>An Idle scope has nothing outstanding, so this returns before narrating anything — no {@code
   * reFired(List.of())} lie. And only {@code ExecuteTool} effects are re-fired: a stalled model
   * call stays the §6.1 staleness arm's job in {@link #drive()}, because a stale {@code
   * ModelFinished} response carries no correlation id — re-firing {@code CallModel} from here could
   * commit a stale response into a later turn.
   */
  void redispatch() {
    State state = wiring.store().load();
    if (state.phase() instanceof Phase.Idle) {
      return;
    }
    List<Effect> outstanding =
        state.phase().outstandingEffects().stream()
            .filter(effect -> effect instanceof Effect.ExecuteTool)
            .toList();
    wiring.observer().reFired(outstanding);
    outstanding.forEach(this::dispatch);
  }

  private boolean isStale() {
    Duration age = Duration.between(wiring.store().lastSaved(), wiring.clock().instant());
    return age.compareTo(wiring.staleThreshold()) >= 0;
  }

  private void dispatch(Effect effect) {
    switch (effect) {
      case Effect.CallModel ignored -> wiring.model().callModel(this::deliver);
      case Effect.ExecuteTool(var call) -> wiring.tools().executeTool(call, this::deliver);
    }
  }
}
