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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.Effect;
import org.jwcarman.nessy.spi.Step;

class EventTest {

  @Test
  void events_are_exhaustively_matchable() {
    Event event = Event.UserSaid.of("hello");

    String described =
        switch (event) {
          case Event.UserSaid e -> "user:" + ((TextBlock) e.content().getFirst()).text();
          case Event.TextDelta e -> "delta:" + e.text();
          case Event.ThinkingDelta e -> "thinking:" + e.text();
          case Event.ThinkingSigned e -> "signed:" + e.signature();
          case Event.RedactedThinkingArrived e -> "redacted:" + e.data();
          case Event.ToolCallRequested e -> "call:" + e.call().name();
          case Event.ModelTurnEnded e -> "end:" + e.reason();
          case Event.ApprovalDecided e -> "approval:" + e.call().name();
          case Event.ToolFinished e -> "finished:" + e.call().name();
          case Event.Compacted e -> "compacted:" + e.workingSet().size();
          case Event.CompactionSkipped e -> "skipped:" + e.reason();
        };

    assertThat(described).isEqualTo("user:hello");
  }

  @Test
  void allow_is_a_shared_instance() {
    assertThat(Decision.allow()).isSameAs(Decision.allow());
  }

  @Test
  void deny_carries_its_reason() {
    Decision decision = new Decision.Deny("user pressed n");

    assertThat(decision).isInstanceOf(Decision.Deny.class);
    assertThat(((Decision.Deny) decision).reason()).isEqualTo("user pressed n");
  }

  @Test
  void step_of_collects_its_effects() {
    SessionState state = SessionState.newSession(new SessionId("s1"));

    Step step = Step.of(state, Effect.callModel());

    assertThat(step.state()).isSameAs(state);
    assertThat(step.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void step_effects_are_unmodifiable() {
    SessionState state = SessionState.newSession(new SessionId("s1"));

    assertThat(Step.of(state).effects()).isUnmodifiable();
  }
}
