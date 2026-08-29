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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.typesafe.config.ConfigFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.TokenEstimator;
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Backlogs;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Coalescer;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.SubstrateBacklogs;
import org.jwcarman.nessy.engine.ToolCallRecord;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * THE MEASUREMENT. The state was fixed last round; the PROMPT was not.
 *
 * <p>{@code recall()} returns the whole conversation, so the prompt grew with every round —
 * unbounded, paid in tokens on every single call, and fatal to the context window long before the
 * database noticed. {@code Context.limitTokens} has existed the whole time and nothing called it.
 *
 * <p>This drives many rounds and measures the recalled prompt both ways: straight from {@code
 * Memory}, and through the budget {@link Memories} applies. One grows; the other stops.
 */
@DisplayName("The size of the prompt a round sends")
class PromptSizeTest {

  private static final long BUDGET = 300;
  private static final TokenEstimator ESTIMATOR = TokenEstimator.heuristic();

  private record Sample(int round, long unbudgetedTokens, long budgetedTokens, int messages) {}

  @Test
  void the_prompt_stops_growing_once_the_budget_binds() throws Exception {
    String agent = "prompt-" + UUID.randomUUID();
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Memories budgeted = new Memories(substrate, BUDGET);
    Memories unbudgeted = new Memories(substrate, Long.MAX_VALUE);
    Backlogs<String> backlogs = new SubstrateBacklogs<>(substrate, Coalescer.none(), String.class);

    WatchmanActorSystem actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedWatchmanModel(Duration.ofMillis(5)),
            new FakeRunner(),
            substrate,
            Coalescer.none(),
            8000,
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10));
    actors.start();

    List<Sample> samples = new ArrayList<>();
    try {
      for (int round = 1; round <= 12; round++) {
        actors.tell(
            agent, new AgentActor.Observe("Round " + round + ". Do your rounds.", Map.of()));

        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(
                () -> {
                  AgentState state = stateOf(actors, agent);
                  assertThat(state.phase()).isInstanceOf(Phase.WorkingTools.class);
                  assertThat(pendingPrune(state)).isPresent();
                });
        String callId = pendingPrune(stateOf(actors, agent)).orElseThrow();
        AgentActor.Ack ack =
            actors
                .answerApproval(agent, callId, false, "james", "no")
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
        System.out.println("  [round " + round + "] answered " + callId + " -> " + ack);
        assertThat(ack.accepted()).isTrue();
        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(
                () -> assertThat(stateOf(actors, agent).phase()).isInstanceOf(Phase.Idle.class));

        Context whole = unbudgeted.everything(agent);
        Context sent = budgeted.forAgent(agent).recall();
        samples.add(
            new Sample(
                round, whole.tokens(ESTIMATOR), sent.tokens(ESTIMATOR), whole.messages().size()));
      }
    } finally {
      actors.stop();
    }

    System.out.println("\n  round | messages | whole transcript | prompt actually sent");
    System.out.println("  ------+----------+------------------+---------------------");
    samples.forEach(
        s ->
            System.out.printf(
                "  %5d | %8d | %16d | %20d%n",
                s.round(), s.messages(), s.unbudgetedTokens(), s.budgetedTokens()));
    System.out.println("  (tokens, heuristic estimator; budget = " + BUDGET + ")\n");

    Sample first = samples.getFirst();
    Sample last = samples.getLast();

    // The conversation really did grow -- this is not a test that passes by doing nothing.
    assertThat(samples).isNotEmpty();
    assertThat(last.messages()).isGreaterThan(first.messages() + 20);
    assertThat(last.unbudgetedTokens()).isGreaterThan(first.unbudgetedTokens() * 3);

    // And what we actually send stopped growing.
    assertThat(last.budgetedTokens())
        .as("the prompt must not track the transcript")
        .isLessThanOrEqualTo(BUDGET);
    assertThat(samples).allSatisfy(s -> assertThat(s.budgetedTokens()).isLessThanOrEqualTo(BUDGET));

    // The budget bound at some point, and after that the prompt is flat.
    assertThat(last.budgetedTokens()).isLessThan(last.unbudgetedTokens());
  }

  private static AgentState stateOf(WatchmanActorSystem actors, String agent) {
    try {
      return actors.inspect(agent).toCompletableFuture().get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Optional<String> pendingPrune(AgentState state) {
    if (!(state.phase() instanceof Phase.WorkingTools working)) {
      return Optional.empty();
    }
    return working.calls().stream()
        .filter(call -> "prune_images".equals(call.tool()))
        .filter(call -> !call.decided() && !call.settled())
        .map(ToolCallRecord::id)
        .findFirst();
  }
}
