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
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.ObservationCoalescer;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.backlog.Backlog;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.Failure;

/**
 * The fold, on its own. No database, no model, no Spring -- a state, an input and a context in, a
 * decision out, which is the whole reason the fold is pure.
 *
 * <p>Numbering is asserted rather than assumed, because the fold is now the only thing that assigns
 * it. Every message says where it sits, a turn is named by the seq of the observation that opened
 * it, and the state carries the counter forward so a later turn cannot reuse a position.
 */
class AgentStateTest {

  private static final Instant T0 = Instant.parse("2026-09-08T12:00:00Z");

  private static final ObservationCoalescer<String> KEEP_ALL = ObservationCoalescer.keepAll();

  private static Decision.Advance<String> advance(Decision<String> decision) {
    assertThat(decision).isInstanceOf(Decision.Advance.class);
    return (Decision.Advance<String>) decision;
  }

  /**
   * What opening a turn looks like now: the fold names the observation and its position, and says
   * nothing about how it reads. Rendering belongs to the store, so a test of the fold cannot see it
   * and has no business asserting on it.
   */
  private static Decision.Opening<String> opening(long seq, String observation) {
    return new Decision.Opening<>(new Seq(seq), observation);
  }

  @Test
  void anIdleAgentObservingOpensATurnAndOwesAModelCall() {
    Decision.Advance<String> decision =
        advance(new AgentState.Idle<String>(new Seq(0)).observe("what is nessy?", T0, KEEP_ALL));

    assertThat(decision.next())
        .isEqualTo(new AgentState.Inferring<>(new Seq(1), new TurnId(1), Backlog.empty()));
    assertThat(decision.recorded())
        .as("the opening is not one of these -- it still has to be rendered")
        .isEmpty();
    assertThat(decision.opening()).isEqualTo(opening(1, "what is nessy?"));
    assertThat(decision.effects()).containsExactly(new AgentEffect.Infer());
  }

  /** An observation opens its own turn, so its seq and its turn are the same number. */
  @Test
  void anObservationIsItsOwnTurn() {
    Decision.Advance<String> decision =
        advance(new AgentState.Idle<String>(new Seq(6)).observe("hello", T0, KEEP_ALL));

    assertThat(decision.opening().seq())
        .as("the opening's seq is the turn's, so a turn needs no id of its own")
        .isEqualTo(new Seq(7));
    assertThat(decision.next())
        .isEqualTo(new AgentState.Inferring<>(new Seq(7), new TurnId(7), Backlog.empty()));
  }

  /** Numbering runs on from what the agent already recorded; a fresh agent starts at one. */
  @Test
  void aLaterTurnNeverReusesAPosition() {
    Decision.Advance<String> decision =
        advance(new AgentState.Idle<String>(new Seq(41)).observe("hello", T0, KEEP_ALL));

    assertThat(decision.next())
        .isEqualTo(new AgentState.Inferring<>(new Seq(42), new TurnId(42), Backlog.empty()));
  }

  @Test
  void anObservationArrivingMidCallWaitsAndNothingIsRecorded() {
    Decision.Advance<String> decision =
        advance(
            new AgentState.Inferring<>(new Seq(1), new TurnId(1), Backlog.<String>empty())
                .observe("and another thing", T0, KEEP_ALL));

    assertThat(decision.next())
        .isEqualTo(
            new AgentState.Inferring<>(
                new Seq(1),
                new TurnId(1),
                Backlog.<String>empty()
                    .accept(new BacklogItem<>("and another thing", T0), KEEP_ALL)));
    assertThat(decision.recorded())
        .as("nothing has happened yet -- the observation has only been queued")
        .isEmpty();
    assertThat(decision.effects())
        .as("a call is already outstanding; a second would answer the same turn twice")
        .isEmpty();
  }

  /**
   * A coalescer that drops the arrival has not made a small change, it has made none -- and the
   * fold must say so. Anything else writes a version bump and logs a transition for an observation
   * that was thrown away.
   */
  @Test
  void anObservationTheCoalescerDropsIsIgnoredRatherThanRecordedAsAFold() {
    Backlog<String> waiting =
        Backlog.<String>empty().accept(new BacklogItem<>("go and look", T0), KEEP_ALL);

    Decision<String> decision =
        new AgentState.Inferring<>(new Seq(1), new TurnId(1), waiting)
            .observe("go and look", T0, ObservationCoalescer.dropRepeats(o -> o));

    assertThat(decision)
        .as("the backlog is unchanged, so nothing happened")
        .isInstanceOf(Decision.Ignore.class);
  }

  /** The application's strategy decides what waits, not the backlog. */
  @Test
  void aCoalescerReplacesAStaleObservationInPlace() {
    Backlog<String> waiting =
        Backlog.<String>empty()
            .accept(new BacklogItem<>("temp=10", T0), KEEP_ALL)
            .accept(new BacklogItem<>("humidity=40", T0), KEEP_ALL);

    Decision.Advance<String> decision =
        advance(
            new AgentState.Inferring<>(new Seq(1), new TurnId(1), waiting)
                .observe("temp=11", T0, ObservationCoalescer.replaceBy(o -> o.split("=")[0])));

    assertThat(decision.next())
        .as(
            "the newer reading takes the older one's place, keeping its position -- "
                + "otherwise a fast sensor perpetually jumps the queue")
        .isEqualTo(
            new AgentState.Inferring<>(
                new Seq(1),
                new TurnId(1),
                Backlog.<String>empty()
                    .accept(new BacklogItem<>("temp=11", T0), KEEP_ALL)
                    .accept(new BacklogItem<>("humidity=40", T0), KEEP_ALL)));
  }

  @Test
  void anAnswerEndsTheTurnAndIsRecordedInIt() {
    Decision.Advance<String> decision =
        advance(
            new AgentState.Inferring<>(new Seq(1), new TurnId(1), Backlog.<String>empty())
                .outcome(
                    new EffectOutcome.InferenceAnswered(
                        HistoryEntry.InferenceAnswered.text("a lake"))));

    assertThat(decision.next()).isEqualTo(new AgentState.Idle<String>(new Seq(2)));
    assertThat(decision.recorded())
        .containsExactly(HistoryEntry.InferenceAnswered.of(2, 1, "a lake"));
    assertThat(decision.effects()).isEmpty();
  }

  /**
   * A turn that ends with something waiting opens the next one immediately -- the answer and the
   * next observation are recorded together, in that order, at consecutive positions, and the new
   * turn is named by the second of them.
   */
  @Test
  void aWaitingObservationOpensTheNextTurnAsTheLastOneCloses() {
    Backlog<String> waiting =
        Backlog.<String>empty().accept(new BacklogItem<>("and another thing", T0), KEEP_ALL);

    Decision.Advance<String> decision =
        advance(
            new AgentState.Inferring<>(new Seq(1), new TurnId(1), waiting)
                .outcome(
                    new EffectOutcome.InferenceAnswered(
                        HistoryEntry.InferenceAnswered.text("a lake"))));

    assertThat(decision.next())
        .isEqualTo(new AgentState.Inferring<>(new Seq(3), new TurnId(3), Backlog.empty()));
    assertThat(decision.recorded())
        .as("the closing answer, and only that -- the opening is not formed here")
        .containsExactly(HistoryEntry.InferenceAnswered.of(2, 1, "a lake"));
    assertThat(decision.opening())
        .as("and the next turn opens on what was waiting, at the very next seq")
        .isEqualTo(opening(3, "and another thing"));
    assertThat(decision.effects()).containsExactly(new AgentEffect.Infer());
  }

  /**
   * A failure closes the turn too, and is recorded -- but never as an answer, which would tell the
   * model on the next turn that it once said something it never said.
   */
  @Test
  void aFailureClosesTheTurnWithItsOwnMessage() {
    Decision.Advance<String> decision =
        advance(
            new AgentState.Inferring<>(new Seq(1), new TurnId(1), Backlog.<String>empty())
                .outcome(
                    new EffectOutcome.InferenceFailed(
                        new Failure.Transient("connection refused"))));

    assertThat(decision.next()).isEqualTo(new AgentState.Idle<String>(new Seq(2)));
    assertThat(decision.recorded())
        .containsExactly(new HistoryEntry.InferenceFailed(new Seq(2), new TurnId(1)));
    assertThat(decision.effects()).isEmpty();
  }

  /** Nothing about the failure itself is recorded: it is ours to act on, not the model's. */
  @Test
  void nothingAboutTheFailureItselfIsRecorded() {
    Decision.Advance<String> decision =
        advance(
            new AgentState.Inferring<>(new Seq(1), new TurnId(1), Backlog.<String>empty())
                .outcome(
                    new EffectOutcome.InferenceFailed(
                        new Failure.Permanent("bearer token abc123 rejected"))));

    assertThat(decision.recorded())
        .containsExactly(new HistoryEntry.InferenceFailed(new Seq(2), new TurnId(1)));
  }

  /** Every arm ends the turn the same way; what differs is what the shell may do about it. */
  @Test
  void everyKindOfFailureClosesTheTurn() {
    List<Failure> failures =
        List.of(
            new Failure.Permanent("refused"),
            new Failure.Transient("503"),
            new Failure.Unknown("read timed out"));

    assertThat(failures)
        .isNotEmpty()
        .allSatisfy(
            failure -> {
              Decision.Advance<String> decision =
                  advance(
                      new AgentState.Inferring<>(new Seq(1), new TurnId(1), Backlog.<String>empty())
                          .outcome(new EffectOutcome.InferenceFailed(failure)));
              assertThat(decision.next()).isEqualTo(new AgentState.Idle<String>(new Seq(2)));
              assertThat(decision.recorded())
                  .containsExactly(new HistoryEntry.InferenceFailed(new Seq(2), new TurnId(1)));
            });
  }

  /**
   * Effects are delivered at least once, so the same answer can arrive twice. The second finds no
   * call outstanding, and writing anything then would put a second answer in the story.
   */
  @Test
  void anOutcomeArrivingAtAnIdleAgentIsIgnoredEntirely() {
    Decision<String> decision =
        new AgentState.Idle<String>(new Seq(2))
            .outcome(
                new EffectOutcome.InferenceAnswered(HistoryEntry.InferenceAnswered.text("a lake")));

    assertThat(decision).isInstanceOf(Decision.Ignore.class);
  }

  // --- termination -------------------------------------------------------------------------

  /** Nothing is outstanding, so there is nothing to wait for. */
  @Test
  void anIdleAgentTerminatesAtOnce() {
    Decision.Advance<String> decision =
        advance(new AgentState.Idle<String>(new Seq(4)).terminate());

    assertThat(decision.next()).isEqualTo(new AgentState.Terminated<String>(new Seq(4)));
    assertThat(decision.recorded())
        .as("terminating is a fact about the agent, not about its conversation")
        .isEmpty();
    assertThat(decision.opening()).isNull();
    assertThat(decision.effects()).isEmpty();
  }

  /**
   * A working agent owes an outcome on an effect row that already exists. It stops accepting now
   * and ends when that turn does -- which is the one moment it asks the backlog for more.
   */
  @Test
  void aWorkingAgentSealsAndEndsWhenItsTurnDoes() {
    Backlog<String> waiting =
        Backlog.<String>empty().accept(new BacklogItem<>("queued", T0), KEEP_ALL);

    Decision.Advance<String> sealing =
        advance(new AgentState.Inferring<>(new Seq(1), new TurnId(1), waiting).terminate());

    assertThat(sealing.next())
        .as("still working, but taking nothing more and keeping nothing that waited")
        .isEqualTo(
            new AgentState.Inferring<>(new Seq(1), new TurnId(1), Backlog.<String>empty().seal()));
    assertThat(sealing.recorded()).isEmpty();

    AgentState<String> sealed = sealing.next();
    Decision.Advance<String> ending =
        advance(
            sealed.outcome(
                new EffectOutcome.InferenceAnswered(
                    HistoryEntry.InferenceAnswered.text("last words"))));

    assertThat(ending.next())
        .as("the pill is discovered when the turn closes, and ends the agent there")
        .isEqualTo(new AgentState.Terminated<String>(new Seq(2)));
    assertThat(ending.recorded())
        .as("the answer is still recorded -- the conversation ended properly")
        .containsExactly(HistoryEntry.InferenceAnswered.of(2, 1, "last words"));
    assertThat(ending.opening()).as("nothing waiting was resurrected").isNull();
  }

  @Test
  void aTerminatedAgentRefusesEverything() {
    AgentState<String> done = new AgentState.Terminated<>(new Seq(7));

    assertThat(done.observe("too late", T0, KEEP_ALL)).isInstanceOf(Decision.Ignore.class);
    assertThat(done.terminate())
        .as("idempotent: a second termination is not a second fold")
        .isInstanceOf(Decision.Ignore.class);
    assertThat(
            done.outcome(
                new EffectOutcome.InferenceFailed(new Failure.Permanent("late redelivery"))))
        .as("its row outlived its own fold; the story is closed")
        .isInstanceOf(Decision.Ignore.class);
  }

  @Test
  void sealingAnAlreadySealedAgentIsIgnored() {
    AgentState<String> sealed =
        new AgentState.Inferring<>(new Seq(1), new TurnId(1), Backlog.<String>empty().seal());

    assertThat(sealed.terminate()).isInstanceOf(Decision.Ignore.class);
  }
}
