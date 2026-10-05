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
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnTallyTest {

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static final TurnId TURN = new TurnId(1);
  private static final CallId CALL = new CallId("c1");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final Usage USAGE = Usage.of("a-model", 100, 20);

  private static List<AgentEvent> story(boolean withDeferrals) {
    List<AgentEvent> story = new ArrayList<>();
    story.add(
        new AgentEvent.TurnStarted(
            new Seq(1), TURN, PayloadRef.of("p"), "Q", Instant.EPOCH, Instant.EPOCH));
    story.add(
        new AgentEvent.ActionsRequested(
            new Seq(2),
            TURN,
            PayloadRef.of("p"),
            List.of(new ActionRequest.ToolCall(CALL, new ToolName("t"), "does it", KEY)),
            USAGE,
            Optional.empty()));
    if (withDeferrals) {
      story.add(
          new AgentEvent.ApprovalDeferred(new Seq(3), TURN, CALL, Instant.EPOCH, none(), KEY));
      story.add(new AgentEvent.ToolDeferred(new Seq(4), TURN, CALL, Instant.EPOCH, KEY));
    }
    story.add(
        new AgentEvent.InferenceAnswered(
            new Seq(story.size() + 1), TURN, PayloadRef.of("a"), false, USAGE, Optional.empty()));
    return story;
  }

  @Test
  void a_turns_stats_are_the_same_with_a_deferral_in_its_events() {
    TurnStats without = TurnTally.of(story(false), TURN);

    TurnStats with = TurnTally.of(story(true), TURN);

    assertThat(without.productiveCalls()).isPositive();
    assertThat(with).isEqualTo(without);
  }

  @Test
  void a_deferral_leaves_the_tally_as_it_was() {
    TurnStats before = TurnStats.opened(Instant.EPOCH).requestedActions(1, USAGE);

    assertThat(
            TurnTally.after(
                before,
                new AgentEvent.ApprovalDeferred(
                    new Seq(3), TURN, CALL, Instant.EPOCH, none(), KEY)))
        .isEqualTo(before);
    assertThat(
            TurnTally.after(
                before, new AgentEvent.ToolDeferred(new Seq(3), TURN, CALL, Instant.EPOCH, KEY)))
        .isEqualTo(before);
  }
}
