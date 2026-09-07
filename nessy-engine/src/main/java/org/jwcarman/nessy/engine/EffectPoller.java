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
      abandonVanished(agentId, rows);
      return;
    }
    for (EffectStore.Attempted attempted : rows) {
      Optional<Instant> stop = stoppedAt(agentId, state.get(), attempted);
      if (stop.isPresent()) {
        stopGroup(agentId, attempted, stop.get());
        return;
      }
    }
  }

  /**
   * Performs one row and says whether this agent's group must STOP here -- and, if so, the moment
   * its un-run siblings should be deferred to.
   *
   * <p>R-AF (Task 7 fix round 4): the ONLY way to stop a group. Every early exit used to be its own
   * {@code return}, each of which had to REMEMBER to release the siblings this pass's own {@link
   * EffectStore#attempt} had already marked RUNNING -- and three separate review rounds found one
   * that had forgotten (R-AC's retry branch, F1's abandon branch, F-NEW's throw branch). Returning
   * an {@link Optional} instead of returning-or-breaking makes that impossible to get wrong: the
   * one caller that acts on a present value is {@link #stopGroup}, which cannot stop without
   * releasing. A sixth way for a row to end has nowhere to say "stop" except here, and saying it
   * costs the deferral instant it must supply.
   *
   * <p>An empty result is every outcome that leaves the line unbroken: a row that quietly succeeded
   * and was {@link EffectStore#complete}d (gone entirely), a parked row whose term lapsed, and --
   * the case that must NOT be flattened into a stop -- an asynchronous hand-off to a model call, a
   * tool or an approver. That row is still RUNNING when {@code perform} returns, but it has not
   * failed: its siblings are supposed to run, and deferring them would be exactly as wrong as
   * leaving them RUNNING after a real failure.
   */
  private Optional<Instant> stoppedAt(
      AgentId agentId, AgentState state, EffectStore.Attempted attempted) {
    try {
      runtime.perform(agentId, state, attempted);
    } catch (RuntimeException failure) {
      // R-AD (Task 7 fix round 3): a throw out of perform() -- an undecodable payload, or any
      // other failure the performer did not itself catch -- stops this pass's group HERE, same as
      // a synchronous retry or abandon does. Deleting AgentRuntime's own catch and catching it
      // here instead is what makes that possible: a bare boolean/void return could not tell
      // "threw" apart from "handed off to async work", so C2's exact continue-instead-of-break
      // shape survived a throw even after the settle/giveUp paths were fixed. THIS row is left
      // RUNNING on purpose -- it IS still outstanding, its watchdog is the right recovery, and C1
      // counts its next pickup as the failure this one genuinely was. Its SIBLINGS are a different
      // question, and the answer is the same as everywhere else: Instant.now(), because a throw
      // never reached EffectStore at all, so there is no instant to inherit and nothing left to
      // wait behind.
      LOG.error(
          "[{}] obligation {} threw and stays outstanding; held back the rest of this pass's group",
          agentId.value(),
          attempted.id(),
          failure);
      return Optional.of(Instant.now());
    }
    EffectStore.Held held = effects.heldAt(attempted.id());
    if (!held.held()) {
      return Optional.empty();
    }
    // A retry read its deferral instant BACK OUT of the row EffectWorker just wrote, rather than
    // recomputing Instant.now().plus(delay): after driver truncation the two are not the same
    // value, and SELECT_DUE's "ORDER BY actionable_at, ordinal" only puts the retried row ahead of
    // its siblings if their instants are byte-identical. An abandonment has no such instant to
    // inherit (ABANDON nulls actionable_at) -- Instant.now() is right there for the same reason it
    // is right for a throw: the group's line is already broken, so the PENDING reset is what
    // matters, not the timing.
    return Optional.of(held.deferSiblingsTo() != null ? held.deferSiblingsTo() : Instant.now());
  }

  /**
   * Retires every row of a group whose agent is GONE, with a reason that says so.
   *
   * <p>R-AH (Task 7 fix round 5). Empty {@code peek} means forgotten, never "not written yet":
   * {@code Transition#record} is the only thing in the engine that ever inserts an effect, and it
   * runs inside the very transaction whose {@code lockAndLoad} brings the agent row into existence,
   * so an effect cannot commit without its agent. What produces this state is the race {@code
   * EffectWorker#forget} can lose -- it deletes an agent's effect rows BEFORE the agent itself, so
   * a transition committing new effects in between leaves rows behind that name an agent already on
   * its way out.
   *
   * <p><b>Abandoned, not skipped.</b> Skipping was C1's disease at a site C1 never looked at: this
   * return is ABOVE {@link AgentRuntime#perform}, so {@code EffectWorker}'s {@code giveUp} is
   * unreachable and nothing could ever close these rows -- they came due on every watchdog lapse
   * forever while {@code TAKE}'s conditional increment charged each pass a failure, {@code
   * attempts} rising without bound and no budget able to spend it. Abandoning applies the standing
   * ruling for an event tied to an unresolvable address -- dropped, and recorded -- with a durable
   * row instead of a log line that scrolls away.
   *
   * <p><b>The whole group, in this pass.</b> Every row here is equally undeliverable, and closing
   * them one watchdog lapse at a time would charge a phantom failure on each lapse along the way --
   * the very cost this exists to stop.
   *
   * <p>The reason names the agent, so {@code nessy_effect} distinguishes "this agent was forgotten
   * out from under its work" from "this obligation failed five times". Those call for completely
   * different responses.
   */
  private void abandonVanished(AgentId agentId, List<EffectStore.Attempted> rows) {
    String reason =
        "agent " + agentId.value() + " no longer exists; forgotten before this obligation ran";
    for (EffectStore.Attempted attempted : rows) {
      effects.abandon(attempted.id(), reason);
    }
    LOG.warn(
        "[{}] {} obligation(s) abandoned: the agent was forgotten before this pass ran them",
        agentId.value(),
        rows.size());
  }

  /**
   * Stops one agent's group at {@code stoppedAt} and releases the siblings behind it in the same
   * breath -- R-AF: there is exactly one way to do the first, and it always does the second.
   *
   * <p>Those siblings were marked RUNNING by THIS pass's own {@link EffectStore#attempt} and then
   * deliberately not run, which is not a failure and must not be charged as one: left RUNNING,
   * {@code TAKE}'s conditional increment reads their next pickup as "found still RUNNING past its
   * watchdog" and charges each a failure it never made, eroding a budget until an engine-owned
   * effect is abandoned and the agent stalls in silence. {@link EffectStore#deferSiblings} puts
   * them back to PENDING, which is what "claimed, then let go" actually means.
   */
  private void stopGroup(AgentId agentId, EffectStore.Attempted attempted, Instant deferTo) {
    int deferred =
        effects.deferSiblings(agentType, agentId, attempted.turnId(), attempted.ordinal(), deferTo);
    LOG.debug(
        "[{}] {} stopped this pass's group; released {} un-run sibling(s) back to PENDING",
        agentId.value(),
        attempted.id(),
        deferred);
  }
}
