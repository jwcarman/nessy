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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Effect;

/**
 * The narrow branches inside {@code EffectWorker} that a full turn cannot be made to hit — not
 * because they are unreachable in production, but because reaching them from the outside would mean
 * contriving the very failure they exist to handle (a claim deleted from under a running call).
 * Each effect here is driven with a real attempt count (0, its first) rather than the sentinel that
 * used to turn {@link RetryPolicy} consultation off. {@code EffectWorker} is package-visible for
 * exactly this: driving one effect directly, against the real dependencies {@link Engines} builds,
 * without needing the decision that would ordinarily have produced it.
 */
@DisplayName("EffectWorker, driven directly at the seams a full turn cannot reach")
class EffectWorkerEdgeCasesTest {

  @Nested
  @DisplayName("a call whose asking message is gone")
  class TheAskingMessageIsGone {

    private static Engines.Parts parts;
    private static AgentId agentId;
    private static AgentType type;

    @BeforeAll
    static void start() {
      type = AgentType.of("orphaned-call");
      parts = Engines.of(type, Engines.stalled());
      agentId = AgentId.of("house-orphaned");
    }

    /**
     * A turn holding a call whose {@code asked} claim was never written — recovery from a state
     * whose asking message somehow never made it to claims, which {@code callOf} treats as "gone"
     * rather than throwing.
     */
    @Test
    @DisplayName("asking approval for it fails the call instead of asking anyone")
    void an_approval_ask_for_a_missing_call_fails_without_asking_anyone() {
      TurnId turnId = TurnId.of("turn-orphaned-approval");
      AgentState state = AgentState.idle().taking(turnId, "obs-claim");
      CallId callId = CallId.of("missing-call");

      parts
          .effectWorker()
          .perform(
              agentId,
              state,
              turnId,
              new Effect.AskApprover(callId, "some_tool"),
              EffectId.next(),
              0);

      ToolResult result = decodedResult(parts, agentId, turnId, callId);
      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message())
          .isEqualTo("the asking message is gone; the call was not made");
    }

    @Test
    @DisplayName("running it fails the call instead of executing anything")
    void a_tool_run_for_a_missing_call_fails_without_running_anything() {
      TurnId turnId = TurnId.of("turn-orphaned-run");
      AgentState state = AgentState.idle().taking(turnId, "obs-claim");
      CallId callId = CallId.of("missing-call-2");

      parts
          .effectWorker()
          .perform(
              agentId, state, turnId, new Effect.RunTool(callId, "some_tool"), EffectId.next(), 0);

      ToolResult result = decodedResult(parts, agentId, turnId, callId);
      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message())
          .isEqualTo("the asking message is gone; it was not run");
    }

    private static ToolResult decodedResult(
        Engines.Parts parts, AgentId agentId, TurnId turnId, CallId callId) {
      byte[] payload =
          parts.claims().get(agentId, turnId, EffectWorker.resultKey(callId)).orElseThrow();
      return JsonCodec.of(EngineMapper.INSTANCE, ToolResult.class).decode(payload);
    }

    /**
     * {@code redeem} is handed {@code state.observation()} as the claim key for {@code
     * Remember.Input}, and that field is {@code null} until an agent has actually taken a row —
     * exactly the state a fresh, never-worked agent is in. {@code redeem} treats a null key as
     * "nothing to redeem" rather than querying a claim that could never exist.
     */
    @Test
    @DisplayName("remembering the input of a turn that never took a row redeems nothing")
    void remembering_input_with_no_observation_claim_redeems_nothing() {
      AgentId neverWorked = AgentId.of("house-never-worked");
      EffectId effectId = claimedEffect(parts, type, neverWorked, null);

      parts
          .effectWorker()
          .perform(neverWorked, AgentState.idle(), null, new Effect.Remember.Input(), effectId, 0);

      assertThat(parts.remembered().of(neverWorked))
          .as("nothing was ever claimed under a null key, so nothing was remembered")
          .isEmpty();
    }
  }

  /**
   * A genuinely RUNNING effect row, so {@code EffectStore#complete} — which now raises if it
   * discharges nothing (see {@code EffectStoreTest}) — has something real to discharge.
   */
  private static EffectId claimedEffect(
      Engines.Parts parts, AgentType type, AgentId agentId, TurnId turnId) {
    EffectId id = parts.effects().insert(type, agentId, turnId, null, 0, new byte[] {0}, null);
    parts.effects().attempt(type, 100, java.time.Instant.now(), java.time.Duration.ofMinutes(1));
    return id;
  }

  @Nested
  @DisplayName("forgetting an agent")
  class Forgetting {

    /**
     * {@code forget} is the only place an agent's state lives now, so there is no second,
     * actor-owned document left to reconcile: wiping the SQL-backed participants -- memory, the
     * backlog, and the state row itself -- IS the whole story.
     */
    @Test
    @DisplayName("memory and the backlog are wiped")
    void forgetting_wipes_memory_and_backlog() {
      AgentType type = AgentType.of("stateless");
      Engines.Parts parts = Engines.of(type, Engines.stalled());
      AgentId agentId = AgentId.of("house-stateless");

      parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
      parts.remembered().add(agentId, answer());
      EffectId effectId = claimedEffect(parts, type, agentId, null);

      parts
          .effectWorker()
          .perform(agentId, AgentState.idle(), null, new Effect.Forget(), effectId, 0);

      assertThat(parts.remembered().of(agentId)).as("memory").isEmpty();
      assertThat(backlogRowCount(parts, agentId)).as("backlog rows").isZero();
    }

    /**
     * C3: forgetting used to leave the agent's own {@code nessy_agent} row and every {@code
     * nessy_effect} row it still owed untouched, so a LATER poll -- there is no reaper, only the
     * same {@code attempt} that would retry a fresh row, see {@code EffectStore}'s class javadoc --
     * would claim and re-perform those effects, calling tools and models for an agent whose memory
     * and claims were already gone, forever. This drives {@code forget} against an agent that
     * genuinely HAS a state row (via {@link AgentStore#save}, reached through the package it lives
     * in) and an outstanding PENDING effect nobody has claimed yet, and proves neither survives:
     * the state row is gone, and a subsequent claim finds nothing -- not the outstanding effect
     * that predates the forget, and not even the {@code Forget} effect's own row, which {@code
     * deleteAgent} sweeps up right along with it.
     */
    @Test
    @DisplayName(
        "an outstanding effect and the state row are both gone, and a subsequent claim finds nothing")
    void forgetting_leaves_no_claimable_effect_and_no_state_row() {
      AgentType type = AgentType.of("effect-laden");
      Engines.Parts parts = Engines.of(type, Engines.stalled());
      AgentId agentId = AgentId.of("house-effect-laden");

      parts.store().save(type, agentId, AgentState.idle().taking(TurnId.of("turn-1"), "obs-claim"));
      // An outstanding obligation nobody has claimed -- exactly what a later poll would otherwise
      // find and re-perform (there is no reaper) against an agent forgetting just erased
      // everything else for.
      parts.effects().insert(type, agentId, TurnId.of("turn-1"), null, 0, new byte[] {0}, null);
      EffectId forgetEffectId = claimedEffect(parts, type, agentId, null);

      parts
          .effectWorker()
          .perform(agentId, AgentState.idle(), null, new Effect.Forget(), forgetEffectId, 0);

      assertThat(
              parts
                  .effects()
                  .attempt(type, 100, java.time.Instant.now(), java.time.Duration.ofMinutes(1)))
          .as("no effect this agent owed is left to claim")
          .isEmpty();
      assertThat(effectRowCount(parts, agentId)).as("nessy_effect rows").isZero();
      assertThat(agentRowCount(parts, type, agentId)).as("nessy_agent rows").isZero();
    }

    private org.jwcarman.nessy.api.message.AnswerMessage answer() {
      return new org.jwcarman.nessy.api.message.AnswerMessage(
          List.of(new org.jwcarman.nessy.api.block.TextBlock("noted")));
    }

    private int effectRowCount(Engines.Parts parts, AgentId agentId) {
      Integer rows =
          org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
              .sql("SELECT count(*) FROM nessy_effect WHERE agent_id = ?")
              .param(agentId.value())
              .query(Integer.class)
              .single();
      return rows == null ? 0 : rows;
    }

    private int agentRowCount(Engines.Parts parts, AgentType type, AgentId agentId) {
      Integer rows =
          org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
              .sql("SELECT count(*) FROM nessy_agent WHERE agent_type = ? AND agent_id = ?")
              .param(type.name())
              .param(agentId.value())
              .query(Integer.class)
              .single();
      return rows == null ? 0 : rows;
    }

    private int backlogRowCount(Engines.Parts parts, AgentId agentId) {
      Integer rows =
          org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
              .sql("SELECT count(*) FROM nessy_backlog WHERE agent_id = ?")
              .param(agentId.value())
              .query(Integer.class)
              .single();
      return rows == null ? 0 : rows;
    }
  }
}
