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
package org.jwcarman.nessy.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only place a durable effect is ever attempted. Supersedes the "reaper" the design used to
 * describe (design of record 2026-09-04, Task 7): there is no separate sweep for abandoned work,
 * because {@link EffectStore#attempt} already finds a row whose watchdog quietly lapsed the exact
 * same way it finds a fresh one -- both are just {@code actionable_at <= now}.
 *
 * <p><b>One virtual thread per AGENT, never per row.</b> A batch is grouped by agent and each
 * agent's rows run sequentially, in {@code ordinal} order, on their own thread -- but different
 * agents run fully in parallel. This is not an optimization left on the table; it is load-bearing.
 * {@code Release} deletes the claims {@code Remember} writes an exchange from, so interleaving two
 * effects that belong to the SAME agent can write an empty exchange and lose a turn with no error
 * at all. All the parallelism worth having here is cross-agent, which is exactly what the row lock
 * {@link EffectStore#attempt} takes already asserts.
 *
 * <p><b>Commit before executing.</b> {@link EffectStore#attempt} marks a row RUNNING and commits
 * before this class -- through {@link AgentRuntime#perform} -- ever calls out to {@link
 * EffectWorker}: a crash between "decided to attempt" and "attempted" is then recoverable -- the
 * row is simply due again once its watchdog lapses -- rather than invisible.
 *
 * <p><b>Exhaustion and retry live in {@link EffectWorker}, not here.</b> {@code EffectWorker
 * #perform} is "the worker" the design of record means when it says the worker checks the {@link
 * RetryPolicy} before doing any work: it already holds the dependencies (the policy, the random
 * source, the dispatcher) that decision needs, and this class has no reason to duplicate them. This
 * class's only job is grouping and sequencing a batch correctly and handing each row, unmodified,
 * to {@link AgentRuntime#perform}.
 *
 * <p><b>No fencing token here, deliberately.</b> A timed-out attempt and the replacement that
 * re-attempts the same row can both eventually finish -- the fold already rejects the second:
 * {@code AgentLogic.awaiting} returns {@code false} once a call is {@code Completed}, and both
 * {@code settle} and {@code onApproval} guard on it. That catches a duplicate outcome whatever its
 * origin (a re-run effect, a late reply token, a duplicated webhook) and lives in the PURE fold,
 * which a row-generation fence could not do. The loser of that race hits {@code EffectStore
 * #complete} raising because it discharges nothing -- an EXPECTED shape of a legitimate
 * timeout-and-restart, not a fault, and {@link AgentRuntime#perform} already logs it at a level
 * that does not read as an error.
 */
final class EffectPoller {

  private static final Logger LOG = LoggerFactory.getLogger(EffectPoller.class);

  private final AgentType agentType;
  private final EffectStore effects;
  private final AgentRuntime runtime;
  private final PollSchedule schedule;
  private final Executor agentExecutor;
  private final int batchSize;
  private final Duration timeout;

  EffectPoller(
      AgentType agentType,
      EffectStore effects,
      AgentRuntime runtime,
      PollSchedule schedule,
      Executor agentExecutor,
      int batchSize,
      Duration timeout) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
    this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    this.schedule = Objects.requireNonNull(schedule, "schedule must not be null");
    this.agentExecutor = Objects.requireNonNull(agentExecutor, "agentExecutor must not be null");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    this.batchSize = batchSize;
  }

  /**
   * One pass: attempt a batch, group it by agent, run every agent's group to completion in
   * parallel, and tell {@link #schedule} what was found. Blocks until every agent group in this
   * batch has finished, so the count it returns is honest -- a caller driving this from a {@code
   * Trigger} measures the NEXT wait from this pass's actual completion, not its start.
   *
   * @return how many rows this pass attempted, which is also what {@link PollSchedule#next} was
   *     just told
   */
  int pollOnce() {
    List<EffectStore.Attempted> batch =
        effects.attempt(agentType, batchSize, Instant.now(), timeout);
    if (!batch.isEmpty()) {
      Map<AgentId, List<EffectStore.Attempted>> byAgent = groupByAgent(batch);
      List<CompletableFuture<Void>> running = new ArrayList<>(byAgent.size());
      for (Map.Entry<AgentId, List<EffectStore.Attempted>> group : byAgent.entrySet()) {
        running.add(
            CompletableFuture.runAsync(
                () -> runAgent(group.getKey(), group.getValue()), agentExecutor));
      }
      running.forEach(CompletableFuture::join);
    }
    schedule.next(batch.size());
    return batch.size();
  }

  /**
   * Groups a batch by agent, and sorts each group by {@code ordinal} -- NOT the order {@code
   * attempt} happened to return, which is {@code actionable_at} order across every agent in the
   * batch and says nothing about one agent's own decision order. Getting this sort wrong is
   * indistinguishable from getting it right on any batch where every agent has at most one row, so
   * {@code EffectPollerTest} deliberately exercises an agent with several.
   */
  private static Map<AgentId, List<EffectStore.Attempted>> groupByAgent(
      List<EffectStore.Attempted> batch) {
    Map<AgentId, List<EffectStore.Attempted>> byAgent = new LinkedHashMap<>();
    for (EffectStore.Attempted attempted : batch) {
      byAgent.computeIfAbsent(attempted.agentId(), key -> new ArrayList<>()).add(attempted);
    }
    byAgent
        .values()
        .forEach(rows -> rows.sort(Comparator.comparingInt(EffectStore.Attempted::ordinal)));
    return byAgent;
  }

  /**
   * One agent's rows, strictly in order, on whichever thread {@code agentExecutor} handed this
   * task. The agent's state is read ONCE per group rather than once per row: nothing a durable
   * effect performs here changes {@code nessy_agent} directly (that happens through {@link
   * AgentRuntime#drive}, on a later input), so a state read partway through a group would not see
   * anything more current -- only cost another round trip.
   */
  private void runAgent(AgentId agentId, List<EffectStore.Attempted> rows) {
    Optional<AgentState> state = runtime.peek(agentId);
    if (state.isEmpty()) {
      // Rare and not a fault: the agent was forgotten between this row becoming due and this pass
      // claiming it. Forget() deletes every effect row it owes BEFORE deleting the agent itself
      // (see EffectWorker#forget), so ordinarily there is nothing left here to find; a row that
      // still turns up belongs to a forget the poller raced and lost cleanly. Left RUNNING, it
      // will be revisited once its watchdog lapses and found gone for good then.
      LOG.debug(
          "[{}] {} rows attempted for an agent with no state; skipped",
          agentId.value(),
          rows.size());
      return;
    }
    for (EffectStore.Attempted attempted : rows) {
      runtime.perform(agentId, state.get(), attempted);
    }
  }
}
