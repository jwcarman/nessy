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
package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * An effect that cannot be performed must still end the turn.
 *
 * <p>The agent is sitting in {@code Inferring} waiting for something to arrive, and nothing else
 * will ever wake it. So giving up is not merely tidying the table -- it owes the agent an answer,
 * even when the answer is that there is none. Without it a single failure parks an agent forever,
 * accepting observations into a backlog it will never drain.
 */
class GiveUpTest {

  private EngineFixture engine;

  /**
   * One engine per test, and each built around the model that test needs.
   *
   * <p>The provider is a factory-level setting -- one model serves every harness an engine hands
   * out -- so a class that varies what the model does varies the engine, not the harness.
   */
  private void running(InferenceProvider model) {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  private Harness<String> harnessThatFails(
      AgentType agentType, RetryPolicy policy, InferenceProvider model) {
    running(model);
    return engine
        .harnesses()
        .create(
            String.class,
            config ->
                config
                    .agentType(agentType)
                    .systemPrompt("You are a test assistant.")
                    .inference(
                        in ->
                            in.model("a-model").retryPolicy(policy).timeout(Duration.ofMinutes(5)))
                    .effects(e -> e.maxInFlight(2).pollInterval(Duration.ofMillis(100))));
  }

  /** The whole record, flattened -- what was stored, not what would be sent. */
  private List<HistoryEntry> story(AgentType agentType, AgentId agentId) {
    return engine.history().entriesFrom(agentType, agentId, 0);
  }

  /**
   * With retries off, the first failure is the last one. This is the configuration that must work
   * on its own: if correctness needed a retry policy, the policy would be load-bearing rather than
   * an optimisation.
   */
  @Test
  void givingUpEndsTheTurnAndLeavesNothingBehind() {
    AgentType type = new AgentType("gives-up");
    AgentId agentId = new AgentId(UUID.randomUUID());
    Harness<String> harness = harnessThatFails(type, new RetryPolicy.Never(), new AlwaysBroken());

    harness.observe(agentId, "will not work");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId))
                  .as("the turn ended, so the agent is not left waiting on a call")
                  .isEqualTo("Idle");
              assertThat(outstandingEffects(agentId))
                  .as("no row left holding an obligation nobody will discharge")
                  .isZero();
            });

    assertThat(story(type, agentId))
        .as("the turn is closed by a failure message, never by an invented answer")
        .containsExactly(
            HistoryEntry.ObservationReceived.opening(
                1, HistoryEntry.ObservationReceived.text("will not work")),
            new HistoryEntry.InferenceFailed(new Seq(2), new TurnId(1)));
  }

  /** A policy with a budget spends it, then gives up the same way. */
  @Test
  void retriesAreSpentBeforeGivingUp() {
    AgentType type = new AgentType("retries");
    AgentId agentId = new AgentId(UUID.randomUUID());
    AlwaysBroken model = new AlwaysBroken();
    Harness<String> harness =
        harnessThatFails(
            type, new RetryPolicy.FixedDelay(3, Duration.ofMillis(50), Duration.ZERO), model);

    harness.observe(agentId, "will not work");

    // Both inside the wait, as every other test of this shape has them. Retiring the effect is
    // its own call rather than part of the write that ends the turn, so reaching Idle does not
    // mean the row has gone yet -- asserting it outside the wait is a race that a slower machine
    // loses, and CI is a slower machine.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId))
                  .as("the turn ended, so the agent is not left waiting on a call")
                  .isEqualTo("Idle");
              assertThat(outstandingEffects(agentId))
                  .as("no row left holding an obligation nobody will discharge")
                  .isZero();
            });

    assertThat(model.calls())
        .as("three attempts, then the turn ends -- not one, and not forever")
        .isEqualTo(3);
  }

  private String agentStateOf(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT state_type FROM nessy_agent_state WHERE agent_id = ?")
        .params(agentId.value())
        .query(String.class)
        .single();
  }

  private int outstandingEffects(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?")
        .params(agentId.value())
        .query(Integer.class)
        .single();
  }

  /** Fails in a way the handler does not catch, so it reaches the retry decision. */
  static class AlwaysBroken implements InferenceProvider {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      calls.incrementAndGet();
      throw new IllegalStateException("nope");
    }

    int calls() {
      return calls.get();
    }
  }
}
