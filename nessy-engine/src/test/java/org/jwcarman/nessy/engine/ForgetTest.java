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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.Input;

/**
 * An agent being forgotten, against a real database.
 *
 * <p>The logic tests say what an agent DECIDES; this says what is actually gone afterwards. The
 * rows are the point — an agent instance that cannot end is a permanent transcript, and this is the
 * test that would fail if forgetting quietly deleted nothing.
 */
@DisplayName("Forgetting an agent")
class ForgetTest {

  private static Engines.Parts parts(String typeName) {
    return Engines.of(
        AgentType.of(typeName),
        Engines.saying(
            java.util.List.of(
                new org.jwcarman.nessy.api.model.ModelResult.Answered(
                    new org.jwcarman.nessy.api.message.AnswerMessage(
                        java.util.List.of(new org.jwcarman.nessy.api.block.TextBlock("done"))),
                    org.jwcarman.nessy.api.model.StopReason.END_TURN,
                    org.jwcarman.nessy.api.model.Usage.unreported()))));
  }

  /** Straight at the table: the point of this test is which rows survive. */
  private static int backlogRows(Engines.Parts parts, AgentId agentId) {
    Integer rows =
        org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
            .sql("SELECT count(*) FROM nessy_backlog WHERE agent_id = ?")
            .param(agentId.value())
            .query(Integer.class)
            .single();
    return rows == null ? 0 : rows;
  }

  /** Whether the pill itself — not merely its symptoms — is still sitting in the table. */
  private static int poisonRows(Engines.Parts parts, AgentId agentId) {
    Integer rows =
        org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
            .sql("SELECT count(*) FROM nessy_poison WHERE agent_id = ?")
            .param(agentId.value())
            .query(Integer.class)
            .single();
    return rows == null ? 0 : rows;
  }

  @Test
  @DisplayName("an idle agent's memory, backlog and claims are gone afterwards")
  void forgetting_an_idle_agent_leaves_nothing() {
    Engines.Parts parts = parts("ephemeral");
    AgentId agentId = AgentId.of("ephemeral-1");

    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(parts.remembered().of(agentId)).isNotEmpty());

    parts.backlog().poison(agentId);
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(parts.remembered().of(agentId)).as("its memory").isEmpty();
              assertThat(backlogRows(parts, agentId)).as("its backlog rows").isZero();
            });
  }

  @Test
  @DisplayName("a busy agent finishes its turn first, and is gone after")
  void forgetting_a_busy_agent_does_not_strand_the_turn() {
    // The failure this design exists to avoid: deleting under a running turn leaves the model's
    // answer arriving at a dead incarnation with nobody left to finish anything.
    Engines.Parts parts = parts("mid-turn");
    AgentId agentId = AgentId.of("mid-turn-1");

    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());
    parts.backlog().poison(agentId);
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(parts.remembered().of(agentId)).isEmpty();
              assertThat(backlogRows(parts, agentId)).isZero();
            });
  }

  @Test
  @DisplayName("a forgotten id is usable again — the pill does not outlive the agent it named")
  void a_forgotten_agent_id_can_be_used_again() {
    // The design's own booby trap: swallow() is the LAST step of forgetting, deliberately, so a
    // crash before it leaves the pill behind. Leaving it forever has no visible cause of its own —
    // the next incarnation of a reusable id would simply never work, poisoned by a forget nobody
    // watching it would ever connect to the symptom.
    Engines.Parts parts = parts("reusable");
    AgentId agentId = AgentId.of("reusable-1");

    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(parts.remembered().of(agentId)).isNotEmpty());

    parts.backlog().poison(agentId);
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(parts.remembered().of(agentId)).isEmpty();
              // The pill is swallowed LAST, deliberately — offering new work before it is gone
              // would have this second offer deleted by the very forget that is still finishing,
              // since `deleteAgent` takes every row for this id and a still-present pill means
              // the next take reads Poisoned rather than the row just offered.
              assertThat(poisonRows(parts, agentId)).as("the pill itself").isZero();
            });

    // A new incarnation of the SAME id, given SAME work. If the pill survived forgetting, the next
    // take would find it poisoned again and this agent would never run — silently, with nothing in
    // this test's view pointing at why.
    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened again"));
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(parts.remembered().of(agentId))
                    .as("the reused id ran a turn instead of being poisoned by the old pill")
                    .isNotEmpty());
  }

  @Test
  @DisplayName("forgetting an agent that never existed is silent")
  void forgetting_nothing_is_not_an_error() {
    Engines.Parts parts = parts("stranger");
    AgentId agentId = AgentId.of("stranger-1");

    parts.backlog().poison(agentId);
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(parts.remembered().of(agentId)).isEmpty());
  }
}
