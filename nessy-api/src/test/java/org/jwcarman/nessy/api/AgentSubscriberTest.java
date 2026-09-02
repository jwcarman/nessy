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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ToolResult;

class AgentSubscriberTest {

  private static final Usage NOTHING = new Usage(0, 0);

  private static List<AgentEvent> everyVariant() {
    return List.of(
        new AgentEvent.TurnStarted("1"),
        new AgentEvent.TextDelta("2", "hi"),
        new AgentEvent.ReasoningDelta("3", "hmm"),
        new AgentEvent.ToolCallRequested("5", CallId.of("c1"), "read_file", "read /etc/hosts"),
        new AgentEvent.ApprovalRequested(
            "6", CallId.of("c1"), "read_file", "read /etc/hosts", Instant.EPOCH),
        new AgentEvent.ApprovalDecided(
            "7", CallId.of("c1"), "read_file", ApprovalResult.approved()),
        new AgentEvent.ToolCallCompleted("8", CallId.of("c1"), "read_file", ToolResult.ok("done")),
        new AgentEvent.Answered("9", new AnswerMessage(List.of())),
        new AgentEvent.TurnEnded("10", new TurnResult.Completed(), NOTHING));
  }

  @Nested
  class Adapter {

    @Test
    void routes_every_variant_to_its_own_hook() {
      List<String> seen = new ArrayList<>();
      AgentSubscriber subscriber =
          new AgentSubscriberAdapter() {
            @Override
            protected void onTurnStarted(AgentEvent.TurnStarted e) {
              seen.add("started");
            }

            @Override
            protected void onTextDelta(AgentEvent.TextDelta e) {
              seen.add("text");
            }

            @Override
            protected void onApprovalRequested(AgentEvent.ApprovalRequested e) {
              seen.add("approval");
            }

            @Override
            protected void onTurnEnded(AgentEvent.TurnEnded e) {
              seen.add("ended");
            }
          };

      everyVariant().forEach(subscriber::on);

      assertThat(seen).containsExactly("started", "text", "approval", "ended");
    }

    @Test
    void hooks_not_overridden_stay_silent() {
      List<String> seen = new ArrayList<>();
      AgentSubscriber subscriber =
          new AgentSubscriberAdapter() {
            @Override
            protected void onTextDelta(AgentEvent.TextDelta e) {
              seen.add(e.text());
            }
          };

      everyVariant().forEach(subscriber::on);

      assertThat(seen).containsExactly("hi");
    }
  }

  @Nested
  class Composition {

    @Test
    void listens_only_to_registered_variants() {
      List<String> seen = new ArrayList<>();
      AgentSubscriber subscriber = AgentSubscriber.of(s -> s.onTextDelta(e -> seen.add(e.text())));

      everyVariant().forEach(subscriber::on);

      assertThat(seen).containsExactly("hi");
    }

    @Test
    void registering_the_same_variant_twice_chains_in_order() {
      List<String> seen = new ArrayList<>();
      AgentSubscriber subscriber =
          AgentSubscriber.of(
              s -> s.onTextDelta(e -> seen.add("journal")).onTextDelta(e -> seen.add("render")));

      subscriber.on(new AgentEvent.TextDelta("1", "hi"));

      assertThat(seen).containsExactly("journal", "render");
    }

    @Test
    void a_config_registering_nothing_hears_everything_silently() {
      AgentSubscriber subscriber = AgentSubscriber.of(s -> {});

      everyVariant().forEach(subscriber::on);

      assertThat(everyVariant()).hasSize(9);
    }
  }

  @Nested
  class Noop {

    @Test
    void accepts_everything_and_tells_no_one() {
      AgentSubscriber subscriber = AgentSubscriber.noop();

      everyVariant().forEach(subscriber::on);

      assertThat(everyVariant()).isNotEmpty();
    }
  }
}
