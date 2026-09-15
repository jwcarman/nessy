package org.jwcarman.nessy.engine.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.EngineUnderTest;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;

/**
 * How much of the story an agent sends: the last so many turns, whole.
 *
 * <p>Against a real PostgreSQL, because the boundary is found by one query and read across by
 * another. That is not something to take on trust: an off-by-one in the window, or a boundary
 * landing inside a turn, produces a context that looks plausible and begins with a reply to a
 * question that is no longer there.
 */
class TurnWindowTest {

  private static EngineUnderTest engine;

  @BeforeAll
  static void startEngine() {
    engine = new EngineUnderTest(NO_MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  /**
   * Nothing here runs a turn: the rows are written directly and the window is read back, because
   * what is under test is the query rather than anything an agent does. A provider that throws is
   * the assertion that no test drifted into starting one.
   */
  private static final InferenceProvider NO_MODEL =
      (_, _) -> {
        throw new AssertionError("this test writes its rows directly and never runs a turn");
      };

  private static final AgentType TYPE = new AgentType("window");

  /** Five turns of an observation and an answer each. */
  private AgentId fiveTurns() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    engine
        .states()
        .save(
            AgentStateRow.initial(
                agentId.value(), TYPE.value(), "Idle", new byte[] {1}, Instant.now()));
    for (int turn = 1; turn <= 9; turn += 2) {
      engine
          .history()
          .append(
              TYPE,
              agentId,
              List.of(
                  HistoryEntry.ObservationReceived.opening(
                      turn, HistoryEntry.ObservationReceived.text("question " + turn)),
                  HistoryEntry.InferenceAnswered.of(turn + 1, turn, "answer " + turn)));
    }
    return agentId;
  }

  @Test
  void aWindowLargerThanTheStoryReturnsEverything() {
    assertThat(engine.history().forAgent(TYPE, fiveTurns()).lastTurns(1_000)).hasSize(5);
  }

  @Test
  void aSmallWindowKeepsTheMostRecentTurns() {
    List<Turn> kept = engine.history().forAgent(TYPE, fiveTurns()).lastTurns(2);

    assertThat(kept).hasSize(2);
    assertThat(kept.stream().map(Turn::id))
        .as("the newest ones, not the first ones written")
        .containsExactly(new TurnId(7), new TurnId(9));
  }

  /**
   * The point of counting turns rather than entries. A boundary inside a turn would hand a model a
   * reply whose question is missing, which reads as nonsense.
   */
  @Test
  void everyTurnReturnedIsWhole() {
    List<Turn> kept = engine.history().forAgent(TYPE, fiveTurns()).lastTurns(2);

    assertThat(kept)
        .allSatisfy(
            turn -> {
              assertThat(turn.observation()).isNotNull();
              assertThat(turn.complete()).isTrue();
              assertThat(turn.result()).isInstanceOf(TurnResult.Answered.class);
            });
  }

  @Test
  void aWindowOfOneIsTheCurrentTurnOnly() {
    List<Turn> kept = engine.history().forAgent(TYPE, fiveTurns()).lastTurns(1);

    assertThat(kept).hasSize(1);
    assertThat(kept.getFirst().id()).isEqualTo(new TurnId(9));
  }

  @Test
  void turnsComeBackOldestFirst() {
    assertThat(engine.history().forAgent(TYPE, fiveTurns()).lastTurns(1_000).stream().map(Turn::id))
        .as("a conversation read backwards is a different conversation")
        .containsExactly(new TurnId(1), new TurnId(3), new TurnId(5), new TurnId(7), new TurnId(9));
  }

  @Test
  void anEmptyStoryReturnsNothingRatherThanFailing() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    engine
        .states()
        .save(
            AgentStateRow.initial(
                agentId.value(), TYPE.value(), "Idle", new byte[] {1}, Instant.now()));

    assertThat(engine.history().forAgent(TYPE, agentId).lastTurns(10)).isEmpty();
  }

  /**
   * The tail after a summary: the same boundary trick with a floor, so the cap is still spent in
   * the query and the boundary is still a whole turn.
   */
  @Test
  void theTailAfterABoundaryIsCappedAndWhole() {
    AgentId agentId = fiveTurns();
    TurnHistory history = engine.history().forAgent(TYPE, agentId);

    assertThat(history.lastTurnsAfter(new TurnId(3), 2).stream().map(Turn::id))
        .as("the newest two after turn 3, not the first two")
        .containsExactly(new TurnId(7), new TurnId(9));
    assertThat(history.lastTurnsAfter(new TurnId(3), 10).stream().map(Turn::id))
        .as("a cap larger than the tail returns the whole tail")
        .containsExactly(new TurnId(5), new TurnId(7), new TurnId(9));
    assertThat(history.lastTurnsAfter(new TurnId(9), 5))
        .as("nothing after the last turn is nothing, not the whole story")
        .isEmpty();
  }

  /** The turn in flight has no result yet, and is still sent -- it is the reason for the call. */
  @Test
  void anUnfinishedTurnIsReturnedOpen() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    engine
        .states()
        .save(
            AgentStateRow.initial(
                agentId.value(), TYPE.value(), "Idle", new byte[] {1}, Instant.now()));
    engine
        .history()
        .append(
            TYPE,
            agentId,
            List.of(
                HistoryEntry.ObservationReceived.opening(
                    1, HistoryEntry.ObservationReceived.text("still thinking"))));

    List<Turn> kept = engine.history().forAgent(TYPE, agentId).lastTurns(5);

    assertThat(kept)
        .singleElement()
        .satisfies(
            turn -> {
              assertThat(turn.complete()).isFalse();
              assertThat(turn.observation().blocks()).isNotEmpty();
            });
  }
}
