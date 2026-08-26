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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@link Agent#ask} (front-ends spec §1): the pattern — subscribe, tell, resolve a {@link
 * TurnOutcome} from the turn's own events, close — over the real {@link
 * org.jwcarman.nessy.agent.host.Nessy#harness} door, with its default virtual-thread executor
 * (never a {@link org.jwcarman.nessy.agent.support.PumpedExecutor}: {@code ask} blocks the calling
 * thread until the turn settles, which only a genuinely async executor can do without deadlocking
 * the very thread meant to pump it).
 */
class AgentAskTest {

  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  @Nested
  class Replied {

    @Test
    void a_scripted_reply_resolves_replied_with_the_exact_text() {
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
      var harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings()));
      HarnessTeardown.track(harness);
      var agent = harness.bind(AgentId.of("scope-1"));

      TurnOutcome outcome = agent.ask("hello");

      assertThat(outcome).isInstanceOf(TurnOutcome.Replied.class);
      assertThat(((TurnOutcome.Replied) outcome).text()).isEqualTo("hello back");
    }
  }

  @Nested
  class Failed {

    @Test
    void a_failing_model_call_resolves_failed_with_the_models_own_reason() {
      Model exploding =
          new Model() {
            @Override
            public ModelStream stream(ModelRequest request) {
              throw new IllegalStateException("boom");
            }

            @Override
            public Set<Capability> capabilities() {
              return Set.of();
            }

            @Override
            public String id() {
              return "exploding";
            }

            @Override
            public String provider() {
              return "test";
            }
          };
      var harness =
          Nessy.harness(
              h ->
                  h.model(exploding)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings()));
      HarnessTeardown.track(harness);
      var agent = harness.bind(AgentId.of("scope-1"));

      TurnOutcome outcome = agent.ask("hello");

      assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
      assertThat(((TurnOutcome.Failed) outcome).reason()).contains("boom");
    }
  }

  @Nested
  class Parked {

    record NoInput() {}

    /** A tool gated behind approval — records every actual execution. */
    private static final class GatedTool implements Tool<NoInput> {

      private final AtomicInteger invocations = new AtomicInteger();

      @Override
      public String name() {
        return "restart";
      }

      @Override
      public String description() {
        return "gated behind approval";
      }

      @Override
      public Class<NoInput> inputType() {
        return NoInput.class;
      }

      @Override
      public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
        invocations.incrementAndGet();
        return Awaited.ready(ToolResult.ok("restarted"));
      }
    }

    /**
     * The brief's central proof: an approval-required tool resolves {@code ask} to {@link
     * TurnOutcome.Parked} carrying the exact ticket whose id grants the turn — approving it through
     * {@link ApprovalDesk} (the same door a production caller uses) resumes the turn to completion.
     */
    @Test
    void an_approval_required_tool_parks_carrying_the_ticket_whose_id_approves_the_turn()
        throws Exception {
      var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
      var tool = new GatedTool();
      var model =
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.ToolUseEmitted(call, null)),
                  List.of(new ModelEvent.TextChunk("done"))));
      var harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .grants(ToolGrant.grant(tool, Approvers.defer())));
      HarnessTeardown.track(harness);
      var agent = harness.bind(AgentId.of("scope-1"));

      TurnOutcome outcome = agent.ask("please restart");

      assertThat(outcome).isInstanceOf(TurnOutcome.Parked.class);
      var parked = (TurnOutcome.Parked) outcome;
      assertThat(parked.request().call().name()).isEqualTo("restart");
      assertThat(parked.request().agentId()).isEqualTo("scope-1");

      var settled = new CompletableFuture<Void>();
      try (Subscription subscription =
          agent.subscribe(
              event -> {
                if (event instanceof TurnEvent.TurnEnded) {
                  settled.complete(null);
                }
              })) {
        harness.approvals().approve(parked.approval(), "test", "");
        settled.get(5, TimeUnit.SECONDS);
      }

      assertThat(tool.invocations).hasValue(1);
    }
  }

  @Nested
  class NoLeak {

    /**
     * ask never leaks its subscription (the brief's fourth proof): {@link
     * Harness#hasSubscribers(AgentId)} is the same package-private registry-emptiness seam {@link
     * AgentSubscriptionTest.ClosingLeaksNothing} uses — checked once for a Replied turn and once
     * for a Failed one, since neither leaves anything parked to clean up later.
     */
    @Test
    void ask_leaks_no_subscription_for_a_replied_turn() {
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi"))));
      var harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings()));
      HarnessTeardown.track(harness);
      var id = AgentId.of("scope-1");
      var agent = harness.bind(id);

      agent.ask("hello");

      assertThat(harness.hasSubscribers(id)).isFalse();
    }

    @Test
    void ask_leaks_no_subscription_for_a_failed_turn() {
      Model exploding =
          new Model() {
            @Override
            public ModelStream stream(ModelRequest request) {
              throw new IllegalStateException("boom");
            }

            @Override
            public Set<Capability> capabilities() {
              return Set.of();
            }

            @Override
            public String id() {
              return "exploding";
            }

            @Override
            public String provider() {
              return "test";
            }
          };
      var harness =
          Nessy.harness(
              h ->
                  h.model(exploding)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings()));
      HarnessTeardown.track(harness);
      var id = AgentId.of("scope-1");
      var agent = harness.bind(id);

      TurnOutcome outcome = agent.ask("hello");

      assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
      assertThat(harness.hasSubscribers(id)).isFalse();
    }
  }

  @Nested
  class OneInFlightPerId {

    /**
     * Fix round 2, I2b: {@link Harness#awaitApproval(AgentId)}'s {@code putIfAbsent}-style guard —
     * a second registration for an id that already has one live throws rather than silently
     * overwriting it (which would orphan the first caller's waiter forever). Proven directly
     * against the seam {@code ask} itself calls, rather than by racing two real threads: {@code
     * ask}'s very first act is registering this wait, before it ever tells anything, so
     * pre-registering it here and then calling {@code ask} reaches the exact same guard a genuine
     * concurrent second caller would.
     */
    @Test
    void a_second_ask_on_an_id_with_a_live_registration_throws_instead_of_orphaning_the_first() {
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
      var harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings()));
      HarnessTeardown.track(harness);
      var id = AgentId.of("scope-1");
      var agent = harness.bind(id);
      var alreadyInFlight = harness.awaitApproval(id);

      try {
        assertThatThrownBy(() -> agent.ask("hello"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("previous ask");
      } finally {
        harness.cancelApprovalWait(id, alreadyInFlight);
      }
    }
  }
}
