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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Narration;

/**
 * Every kind of narration is somebody's job to say.
 *
 * <p><b>Why this exists.</b> {@link Narration}'s arms are declared in the api and produced in the
 * engine, and nothing connects the two: a producer can be deleted and everything still compiles,
 * because an arm nobody constructs is not an error. That is not hypothetical -- the fold rewrite
 * silently dropped FIVE of them at once (thinking, commentary, the answer, the end of a turn, and
 * the words an input arrived with), and the only symptom was watchers quietly hearing less.
 *
 * <p>So this pins the roster rather than the behaviour. Adding an arm fails here until it is
 * written into one of the two lists, which is the moment to decide who says it; the end-to-end
 * narration tests then pin that it is actually said. Neither test alone would have caught the five.
 */
class NarrationCoverageTest {

  /**
   * Said by the engine, after a fold has committed, and therefore true when it is said.
   *
   * <p>Each one is asserted somewhere: a turn end to end in {@code NarrationTest}, the denials in
   * {@code ToolCallingTest}, ending in {@code TerminationAndConfigurationTest}. The two deferrals
   * are told from the stored events that record them, as {@code StoryEventsTest} pins.
   */
  private static final Set<String> SAID_BY_THE_ENGINE =
      Set.of(
          "TurnStarted",
          "Thinking",
          "Answered",
          "TurnStopped",
          "InferenceRetried",
          "TurnFailed",
          "TurnRefused",
          "Commentary",
          "ActionsRequested",
          "CallApproved",
          "CallDenied",
          "CallFinished",
          "CallFailed",
          "Terminated",
          "ApprovalSought",
          "ApprovalDeferred",
          "CallDeferred");

  /**
   * Said by a provider while a call is still in flight, through {@code InferenceNarrator}.
   *
   * <p>True only of the attempt that is streaming: a call that fails and is retried narrates these
   * twice, which is why they are not facts and why no fold produces them.
   */
  private static final Set<String> SAID_BY_A_PROVIDER = Set.of("ThinkingDelta", "ContentDelta");

  @Test
  @DisplayName("every arm of the narration grammar has somebody whose job it is to say it")
  void every_arm_is_accounted_for() {
    Set<String> declared =
        new TreeSet<>(arms(Narration.class).stream().map(Class::getSimpleName).toList());

    Set<String> accounted = new TreeSet<>(SAID_BY_THE_ENGINE);
    accounted.addAll(SAID_BY_A_PROVIDER);

    assertThat(declared)
        .as(
            "an arm nobody says is an arm nobody hears -- add it to one of the two lists in this"
                + " test, and then to whoever should be saying it")
        .isEqualTo(accounted);
  }

  @Test
  @DisplayName("the two jobs do not overlap")
  void nothing_is_both_a_fact_and_a_delta() {
    Set<String> both = new TreeSet<>(SAID_BY_THE_ENGINE);
    both.retainAll(SAID_BY_A_PROVIDER);

    assertThat(both)
        .as("a fact is announced once, after a fold; a delta is what a provider is saying now")
        .isEmpty();
  }

  /** The records at the leaves of the sealed grammar; the groups in between are not arms. */
  private static List<Class<?>> arms(Class<?> group) {
    return Arrays.stream(group.getPermittedSubclasses())
        .flatMap(member -> member.isInterface() ? arms(member).stream() : Stream.of(member))
        .toList();
  }
}
