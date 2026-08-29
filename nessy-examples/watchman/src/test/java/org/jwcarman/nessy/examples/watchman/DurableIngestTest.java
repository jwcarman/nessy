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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Calls;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.StateSerializer;

/**
 * DURABLE INGEST — the requirement that a human's answer is never acknowledged before it is safe.
 *
 * <p>The guarantee comes from the order Pekko runs an effect's stages: {@code persist} completes,
 * then {@code thenRun}, then {@code thenReply}. What these tests add is proof that {@link
 * ApprovalsController} is actually standing on that ordering rather than merely near it — which is
 * easy to get wrong, because swapping the {@code ask} for a {@code tell} and returning a String
 * still compiles, still passes a happy-path flow test, and quietly loses answers.
 */
@Tag("container")
@DisplayName("Answering an approval durably")
class DurableIngestTest {

  private static final Duration PATIENCE = Duration.ofSeconds(45);

  private final DataSource dataSource = WatchmanPostgres.dataSource();

  private AgentState stateOf(WatchmanActorSystem actors, String agent) {
    try {
      return actors.inspect(agent).toCompletableFuture().get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Straight out of Postgres, bypassing every actor: what is ACTUALLY on disk right now. */
  private Optional<AgentState> onDisk(String agent) {
    StateSerializer codec = new StateSerializer();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT state_payload FROM durable_state WHERE persistence_id = ?")) {
      statement.setString(1, "Watchman|" + agent);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(
            (AgentState) codec.fromBinary(rows.getBytes(1), StateSerializer.AGENT_STATE_V2));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void parkOnApproval(WatchmanActorSystem actors, String agent) {
    actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", java.util.Map.of()));
    await()
        .atMost(PATIENCE)
        .untilAsserted(
            () -> {
              AgentState state = stateOf(actors, agent);
              assertThat(state.phase()).isInstanceOf(Phase.WorkingTools.class);
              assertThat(Calls.pending(state, "prune_images")).isPresent();
            });
  }

  /** The pending prune call's id, which is unique per call and never hardcoded. */
  private static String prune(WatchmanActorSystem actors, String agent) {
    try {
      return Calls.pending(
              actors.inspect(agent).toCompletableFuture().get(20, TimeUnit.SECONDS), "prune_images")
          .orElseThrow();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void the_decision_is_on_disk_before_the_page_is_told_anything() throws Exception {
    String agent = "ingest-" + UUID.randomUUID();
    try (var ignored =
        new AutoCloseableActors(
            WatchmanPostgres.start(new ScriptedWatchmanModel(Duration.ofMillis(20))))) {
      WatchmanActorSystem actors = ignored.actors();
      parkOnApproval(actors, agent);
      AgentState parked = onDisk(agent).orElseThrow();
      assertThat(parked.phase()).isInstanceOf(Phase.WorkingTools.class);
      assertThat(Calls.byTool(parked, "prune_images").orElseThrow().decided()).isFalse();

      AgentActor.Ack ack =
          actors
              .answerApproval(agent, prune(actors, agent), false, "james", "not today")
              .toCompletableFuture()
              .get(20, TimeUnit.SECONDS);

      // The instant the page is allowed to render a redirect, the answer is already in Postgres.
      // Read directly, with no actor involved, on the very next statement.
      assertThat(ack.accepted()).isTrue();
      AgentState persisted = onDisk(agent).orElseThrow();
      assertThat(deniedBy(agent, persisted, "james", "not today"))
          .as("the denial must be durable before the page is told anything")
          .isTrue();
    }
  }

  @Test
  void an_answer_survives_the_process_dying_the_moment_after_it_is_acknowledged() throws Exception {
    String agent = "ingest-crash-" + UUID.randomUUID();

    try (var first =
        new AutoCloseableActors(
            WatchmanPostgres.start(new ScriptedWatchmanModel(Duration.ofMillis(20))))) {
      parkOnApproval(first.actors(), agent);
      first
          .actors()
          .answerApproval(agent, prune(first.actors(), agent), false, "james", "absolutely not")
          .toCompletableFuture()
          .get(20, TimeUnit.SECONDS);
      // Terminate immediately: if the acknowledgement had raced ahead of the write, this is where
      // the answer would be lost.
    }

    try (var second =
        new AutoCloseableActors(
            WatchmanPostgres.start(new ScriptedWatchmanModel(Duration.ofMillis(20))))) {
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () ->
                  assertThat(stateOf(second.actors(), agent).phase())
                      .isInstanceOf(Phase.Idle.class));

      assertThat(WatchmanPostgres.results(agent).values())
          .anySatisfy(text -> assertThat(text).contains("denied by james: absolutely not"));
    }
  }

  /**
   * Is the denial recorded anywhere durable?
   *
   * <p>Two places, and the test accepts either, for a reason that is itself a finding. A
   * DurableStateBehavior stores only the CURRENT state, so the decision lives on the call record
   * for exactly as long as the round is still working tools. Denying the LAST unsettled call
   * settles the round in the same breath, and the very next write replaces that state with one
   * where the denial survives only as a tool result in the transcript. Both are durable, so both
   * satisfy the requirement -- but which one you will find is a race, and that is worth knowing.
   * See the report on what this costs the audit trail.
   */
  private static boolean deniedBy(String agentId, AgentState state, String by, String note) {
    String expected = "denied by " + by + ": " + note;
    if (state.phase() instanceof Phase.WorkingTools working) {
      boolean onTheCall =
          working.calls().stream()
              .anyMatch(
                  call ->
                      call.decided()
                          && !call.decision().approved()
                          && by.equals(call.decision().by())
                          && note.equals(call.decision().note()));
      if (onTheCall) {
        return true;
      }
    }
    return WatchmanPostgres.results(agentId).values().stream()
        .anyMatch(text -> text.contains(expected));
  }

  /** Small shim so the tests can use try-with-resources over the lifecycle bean. */
  private record AutoCloseableActors(WatchmanActorSystem actors) implements AutoCloseable {
    @Override
    public void close() {
      actors.stop();
    }
  }
}
