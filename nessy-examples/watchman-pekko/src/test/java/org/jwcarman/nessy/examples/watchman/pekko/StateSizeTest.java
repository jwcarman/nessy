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
package org.jwcarman.nessy.examples.watchman.pekko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THE MEASUREMENT. The soak found the durable state growing with the conversation, because a {@code
 * DurableStateBehavior} rewrites its whole document on every revision and the transcript was inside
 * it:
 *
 * <pre>
 *   revision  5   1,709 bytes
 *   revision 64  24,151 bytes      (64 minutes later; all 64 revisions rewrote everything)
 * </pre>
 *
 * <p>The transcript now lives in Memory, appended one row per turn. This test drives many rounds
 * against real Postgres and asserts what should now be true: the revision climbs and the state
 * payload does NOT.
 */
@Tag("container")
@DisplayName("The size of what a round rewrites")
class StateSizeTest {

  /** One sample of the row the agent rewrites on every fold. */
  private record Sample(long revision, int bytes) {}

  private Sample sample(String agent) {
    try (Connection connection = WatchmanPostgres.dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT revision, octet_length(state_payload) FROM durable_state"
                    + " WHERE persistence_id = ?")) {
      statement.setString(1, "Watchman|" + agent);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? new Sample(rows.getLong(1), rows.getInt(2)) : new Sample(0, 0);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The id changes every round, exactly as a real model's would. */
  private static java.util.Optional<String> pendingPrune(TurnState state) {
    if (!(state instanceof TurnState.WorkingTools working)) {
      return java.util.Optional.empty();
    }
    return working.calls().stream()
        .filter(call -> "prune_images".equals(call.tool()))
        .filter(call -> !call.decided() && !call.settled())
        .map(ToolCallRecord::id)
        .findFirst();
  }

  private TurnState stateOf(WatchmanActorSystem actors, String agent) {
    try {
      return actors.inspect(agent).toCompletableFuture().get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void the_state_payload_stays_flat_while_the_revision_climbs() throws Exception {
    String agent = "size-" + UUID.randomUUID();
    Memories memories = WatchmanPostgres.memories();
    List<Sample> samples = new ArrayList<>();
    List<Sample> parked = new ArrayList<>();

    WatchmanActorSystem actors =
        WatchmanPostgres.start(new ScriptedWatchmanModel(Duration.ofMillis(5)));
    try {
      // Twelve full rounds. Each is: user turn, model call, three tool calls (one of them denied
      // by a human), a second model call, done -- around six revisions and six transcript turns.
      for (int round = 1; round <= 12; round++) {
        WatchmanPostgres.observe(agent, "Round " + round + ". Do your rounds.");
        actors.tell(
            agent,
            new AgentActor.Observe(
                "Round " + round + ". Do your rounds.", "rounds", java.util.Map.of()));

        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(
                () -> {
                  TurnState state = stateOf(actors, agent);
                  assertThat(state).isInstanceOf(TurnState.WorkingTools.class);
                  assertThat(pendingPrune(state)).isPresent();
                });

        // The mid-round peak: the state is at its largest here, holding all three calls.
        parked.add(sample(agent));
        actors
            .answerApproval(
                agent, pendingPrune(stateOf(actors, agent)).orElseThrow(), false, "james", "no")
            .toCompletableFuture()
            .get(20, TimeUnit.SECONDS);
        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(
                () -> assertThat(stateOf(actors, agent)).isInstanceOf(TurnState.Idle.class));

        samples.add(sample(agent));
      }
    } finally {
      actors.stop();
    }

    int turns = memories.everything(agent).messages().size();
    System.out.println("\n  round | revision | idle bytes | mid-round bytes");
    System.out.println("  ------+----------+------------+----------------");
    for (int i = 0; i < samples.size(); i++) {
      System.out.printf(
          "  %5d | %8d | %10d | %15d%n",
          i + 1, samples.get(i).revision(), samples.get(i).bytes(), parked.get(i).bytes());
    }
    System.out.printf("  messages recalled from Memory: %d%n%n", turns);

    Sample first = samples.getFirst();
    Sample last = samples.getLast();

    // The revision really did climb -- this is not a test that passes by doing nothing.
    assertThat(samples).isNotEmpty();
    assertThat(last.revision()).isGreaterThan(first.revision() + 20);
    assertThat(turns).isGreaterThan(20);

    // And the payload did not. Every round returns the agent to Idle, whose document is the same
    // handful of bytes no matter how long the conversation has become.
    assertThat(last.bytes())
        .as("the state must not grow with the transcript")
        .isEqualTo(first.bytes());
    assertThat(samples).allSatisfy(s -> assertThat(s.bytes()).isLessThan(512));

    // The mid-round peak is bounded by the number of calls in ONE round, not by history. It is
    // not byte-identical across rounds, and the reason is worth knowing: round 12's call ids are
    // three characters longer than round 1's. That is id length, not conversation length -- the
    // difference over twelve rounds is single-digit bytes, against 22 KB/hour before.
    assertThat(parked).isNotEmpty();
    int smallest = parked.stream().mapToInt(Sample::bytes).min().orElseThrow();
    int largest = parked.stream().mapToInt(Sample::bytes).max().orElseThrow();
    assertThat(largest - smallest)
        .as("the mid-round peak must not track the transcript")
        .isLessThan(32);
    assertThat(parked).allSatisfy(s -> assertThat(s.bytes()).isLessThan(2048));
  }
}
