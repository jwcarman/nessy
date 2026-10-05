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
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.RequestManifest;
import org.jwcarman.nessy.engine.inference.Manifests;
import org.jwcarman.nessy.inference.Failure;

/**
 * What a model-call event is recorded with: the manifest its outcome carried, copied over and
 * decided by nothing.
 */
@DisplayName("A model-call event records what its request was made of")
class AgentStateManifestTest {

  private static final PayloadRef MAIL = PayloadRef.of("payload-1");
  private static final PayloadRef ANSWER = PayloadRef.of("payload-2");
  private static final PayloadRef ASKED = PayloadRef.of("payload-3");
  private static final TurnId TURN = new TurnId(1);
  private static final CallId CALL = new CallId("call-1");
  private static final ToolName TOOL = new ToolName("refund");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("00000000-0000-7000-8000-000000000001"));
  private static final List<ActionRequest> CALLS =
      List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool", KEY));
  private static final Usage USAGE = Usage.of("a-model", 11, 7);

  private final AgentState idle = AgentState.idle(Seq.NONE);

  private AgentState inferring() {
    return idle.applyAll(
        idle.execute(new AgentCommand.StartTurn(MAIL, "Question", Instant.EPOCH, Instant.EPOCH))
            .events());
  }

  private static AgentCommand.CompleteInference complete(
      AgentCommand.InferenceOutcome outcome, FailedAttempt... prior) {
    return new AgentCommand.CompleteInference(TURN, outcome, List.of(prior));
  }

  @Nested
  @DisplayName("each closing event")
  class ClosingEvents {

    @Test
    @DisplayName("an answer is recorded with what its request was made of")
    void an_answer_is_recorded_with_what_its_request_was_made_of() {
      AgentState inferring = inferring();
      RequestManifest manifest = Manifests.numbered(1);

      Decision decision =
          inferring.execute(
              complete(
                  new AgentCommand.InferenceOutcome.Answered(
                      ANSWER, false, USAGE, Optional.of(manifest))));

      assertThat(decision.events())
          .containsExactly(
              new AgentEvent.InferenceAnswered(
                  inferring.seq().next(), TURN, ANSWER, false, USAGE, Optional.of(manifest)));
    }

    @Test
    @DisplayName("a refusal is recorded with what its request was made of")
    void a_refusal_is_recorded_with_what_its_request_was_made_of() {
      AgentState inferring = inferring();
      RequestManifest manifest = Manifests.numbered(2);

      Decision decision =
          inferring.execute(
              complete(
                  new AgentCommand.InferenceOutcome.Refused(
                      "safety", USAGE, Optional.of(manifest))));

      assertThat(decision.events())
          .containsExactly(
              new AgentEvent.InferenceRefused(
                  inferring.seq().next(), TURN, "safety", USAGE, Optional.of(manifest)));
    }

    @Test
    @DisplayName("a failure is recorded with what its request was made of")
    void a_failure_is_recorded_with_what_its_request_was_made_of() {
      AgentState inferring = inferring();
      RequestManifest manifest = Manifests.numbered(3);
      Failure reason = new Failure.Transient("provider unreachable");

      Decision decision =
          inferring.execute(
              complete(
                  new AgentCommand.InferenceOutcome.Failed(reason, USAGE, Optional.of(manifest))));

      assertThat(decision.events())
          .containsExactly(
              new AgentEvent.InferenceFailed(
                  inferring.seq().next(), TURN, reason, USAGE, Optional.of(manifest)));
    }

    @Test
    @DisplayName("requested actions are recorded with what their request was made of")
    void requested_actions_are_recorded_with_what_their_request_was_made_of() {
      AgentState inferring = inferring();
      RequestManifest manifest = Manifests.numbered(4);

      Decision decision =
          inferring.execute(
              complete(
                  new AgentCommand.InferenceOutcome.RequestedActions(
                      ASKED, CALLS, USAGE, Optional.of(manifest))));

      assertThat(decision.events())
          .containsExactly(
              new AgentEvent.ActionsRequested(
                  inferring.seq().next(), TURN, ASKED, CALLS, USAGE, Optional.of(manifest)));
    }

    @Test
    @DisplayName("an event with no manifest in hand records none")
    void an_event_with_no_manifest_in_hand_records_none() {
      AgentState inferring = inferring();

      Decision decision =
          inferring.execute(
              complete(
                  new AgentCommand.InferenceOutcome.Failed(
                      new Failure.Unknown("never heard back"), USAGE, Optional.empty())));

      assertThat(decision.events())
          .containsExactly(
              new AgentEvent.InferenceFailed(
                  inferring.seq().next(),
                  TURN,
                  new Failure.Unknown("never heard back"),
                  USAGE,
                  Optional.empty()));
    }
  }

  @Nested
  @DisplayName("retried attempts")
  class RetriedAttempts {

    private final Failure busy = new Failure.Transient("busy");

    @Test
    @DisplayName(
        "each retried attempt is recorded with its own manifest and the closing event with its own")
    void
        each_retried_attempt_is_recorded_with_its_own_manifest_and_the_closing_event_with_its_own() {
      AgentState inferring = inferring();
      RequestManifest first = Manifests.numbered(1);
      RequestManifest second = Manifests.numbered(2);
      RequestManifest third = Manifests.numbered(3);
      Usage firstUsage = Usage.of("a-model", 1, 0);
      Usage secondUsage = Usage.of("a-model", 2, 0);
      Seq at = inferring.seq();

      Decision decision =
          inferring.execute(
              complete(
                  new AgentCommand.InferenceOutcome.Answered(
                      ANSWER, false, USAGE, Optional.of(third)),
                  new FailedAttempt(busy, firstUsage, Optional.of(first)),
                  new FailedAttempt(busy, secondUsage, Optional.of(second))));

      assertThat(decision.events())
          .containsExactly(
              new AgentEvent.InferenceAttempted(
                  at.next(), TURN, busy, firstUsage, Optional.of(first)),
              new AgentEvent.InferenceAttempted(
                  at.next().next(), TURN, busy, secondUsage, Optional.of(second)),
              new AgentEvent.InferenceAnswered(
                  at.next().next().next(), TURN, ANSWER, false, USAGE, Optional.of(third)));
    }

    @Test
    @DisplayName("an attempt that has none stays without one beside attempts that have")
    void an_attempt_that_has_none_stays_without_one_beside_attempts_that_have() {
      AgentState inferring = inferring();
      RequestManifest first = Manifests.numbered(1);
      RequestManifest third = Manifests.numbered(3);
      Seq at = inferring.seq();

      Decision decision =
          inferring.execute(
              complete(
                  new AgentCommand.InferenceOutcome.Answered(
                      ANSWER, false, USAGE, Optional.of(third)),
                  new FailedAttempt(busy, USAGE, Optional.of(first)),
                  new FailedAttempt(busy, USAGE, Optional.empty())));

      assertThat(decision.events())
          .containsExactly(
              new AgentEvent.InferenceAttempted(at.next(), TURN, busy, USAGE, Optional.of(first)),
              new AgentEvent.InferenceAttempted(
                  at.next().next(), TURN, busy, USAGE, Optional.empty()),
              new AgentEvent.InferenceAnswered(
                  at.next().next().next(), TURN, ANSWER, false, USAGE, Optional.of(third)));
    }
  }

  @Nested
  @DisplayName("the manifest")
  class DecidesNothing {

    static Stream<Arguments> outcomes() {
      return Stream.of(
          Arguments.of(
              "an answer",
              (Function<Optional<RequestManifest>, AgentCommand.InferenceOutcome>)
                  m -> new AgentCommand.InferenceOutcome.Answered(ANSWER, false, USAGE, m)),
          Arguments.of(
              "a refusal",
              (Function<Optional<RequestManifest>, AgentCommand.InferenceOutcome>)
                  m -> new AgentCommand.InferenceOutcome.Refused("safety", USAGE, m)),
          Arguments.of(
              "a failure",
              (Function<Optional<RequestManifest>, AgentCommand.InferenceOutcome>)
                  m ->
                      new AgentCommand.InferenceOutcome.Failed(
                          new Failure.Transient("down"), USAGE, m)),
          Arguments.of(
              "requested actions",
              (Function<Optional<RequestManifest>, AgentCommand.InferenceOutcome>)
                  m -> new AgentCommand.InferenceOutcome.RequestedActions(ASKED, CALLS, USAGE, m)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outcomes")
    @DisplayName(
        "changes no decision: the effects and the next state are the same with or without it")
    void the_manifest_changes_no_decision(
        String name, Function<Optional<RequestManifest>, AgentCommand.InferenceOutcome> outcome) {
      AgentState inferring = inferring();

      Decision with =
          inferring.execute(complete(outcome.apply(Optional.of(Manifests.numbered(5)))));
      Decision without = inferring.execute(complete(outcome.apply(Optional.empty())));

      assertThat(with.effects()).isEqualTo(without.effects());
      assertThat(inferring.applyAll(with.events())).isEqualTo(inferring.applyAll(without.events()));
      assertThat(with.events()).hasSameSizeAs(without.events());
    }
  }

  @Nested
  @DisplayName("replay")
  class Replay {

    @Test
    @DisplayName("of events that carry manifests builds the state the live decisions built")
    void replay_of_events_with_manifests_equals_the_live_state() {
      AgentState live = idle;
      List<AgentEvent> story = new ArrayList<>();
      Decision start =
          live.execute(new AgentCommand.StartTurn(MAIL, "Question", Instant.EPOCH, Instant.EPOCH));
      story.addAll(start.events());
      live = live.applyAll(start.events());
      Decision asked =
          live.execute(
              complete(
                  new AgentCommand.InferenceOutcome.RequestedActions(
                      ASKED, CALLS, USAGE, Optional.of(Manifests.numbered(1))),
                  new FailedAttempt(
                      new Failure.Transient("busy"), USAGE, Optional.of(Manifests.numbered(2)))));
      story.addAll(asked.events());
      live = live.applyAll(asked.events());

      assertThat(story)
          .filteredOn(AgentEvent.ActionsRequested.class::isInstance)
          .singleElement()
          .isEqualTo(
              new AgentEvent.ActionsRequested(
                  Seq.of(3), TURN, ASKED, CALLS, USAGE, Optional.of(Manifests.numbered(1))));
      assertThat(AgentState.idle(Seq.NONE).applyAll(story)).isEqualTo(live);
    }
  }
}
