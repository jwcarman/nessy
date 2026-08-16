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
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;
import org.jwcarman.nessy.spi.transcript.Transcript;
import org.slf4j.LoggerFactory;

/**
 * {@code AgentConfig}'s own validation and configuration wiring: every setter actually reaches the
 * {@link org.jwcarman.nessy.internal.ConversationLoop} it configures, isolated from the full build
 * path {@code HarnessTest} exercises for the model-resolution and declared-listening stories.
 */
class AgentConfigTest {

  private static final ModelProvider NEVER_CALLED =
      new ModelProvider() {
        @Override
        public ModelStream stream(ModelRequest request) {
          throw new AssertionError("never called");
        }

        @Override
        public Set<Capability> capabilities() {
          return Set.of();
        }
      };

  /** A model that replays one scripted text turn per call and records every request it saw. */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<String> replies;
    private final List<ModelRequest> requests = new ArrayList<>();

    FakeProvider(String... replies) {
      this.replies = new ArrayDeque<>(List.of(replies));
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      requests.add(request);
      List<ModelEvent> turn =
          List.of(
              new ModelEvent.TextChunk(replies.removeFirst()),
              new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // intentionally empty: this fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  record Nothing() {}

  private static final class NoOpTool implements Tool<Nothing> {
    @Override
    public String name() {
      return "noop";
    }

    @Override
    public String description() {
      return "Does nothing";
    }

    @Override
    public Class<Nothing> inputType() {
      return Nothing.class;
    }

    @Override
    public Awaited<ToolResult> execute(Nothing input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("done"));
    }
  }

  @Nested
  class Tools_by_grant {

    @Test
    void a_null_grants_array_is_rejected() {
      ToolGrant[] grants = null;
      var agent =
          new AgentConfig<>(
                  Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text())
              .name("scribe");

      assertThatThrownBy(() -> agent.tools(grants))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("grants");
    }

    @Test
    void a_null_element_in_the_grants_array_is_rejected() {
      ToolGrant present = ToolGrant.grant(new NoOpTool(), UsagePolicy.allow());
      var agent =
          new AgentConfig<>(
                  Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text())
              .name("scribe");

      assertThatThrownBy(() -> agent.tools(present, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("grants[1]");
    }

    @Test
    void an_empty_grants_array_registers_no_tools() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(a -> a.name("scribe").model("fake-model").tools());
      TextObserver observer = new TextObserver();

      agent.converse().tell("hi", observer);

      assertThat(observer.text()).isEqualTo("hi");
    }
  }

  @Nested
  class Configuration {

    @Test
    void the_system_prompt_max_tokens_and_capabilities_all_reach_the_request() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .systemPrompt("be terse")
                          .maxTokens(777)
                          .capabilities(Set.of(Capability.PROMPT_CACHING)));

      agent.converse().tell("hi");

      ModelRequest request = provider.requests().getFirst();
      assertThat(request.systemPrompt()).isEqualTo("be terse");
      assertThat(request.maxTokens()).isEqualTo(777);
      assertThat(request.requested()).containsExactly(Capability.PROMPT_CACHING);
    }

    @Test
    void a_blank_model_falls_back_to_the_harness_default_exactly_like_no_model_at_all() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(h -> h.provider(provider).defaultModel("harness-default"))
              .agent(a -> a.name("scribe").model("  "));

      agent.converse().tell("hi");

      assertThat(provider.requests().getFirst().model()).isEqualTo("harness-default");
    }

    @Test
    void the_approver_override_replaces_the_default_allow_all() {
      var call = new ToolCall("c1", "noop", JsonNodeFactory.instance.objectNode());
      ModelProvider provider =
          new ModelProvider() {
            private final Deque<List<ModelEvent>> turns =
                new ArrayDeque<>(
                    List.of(
                        List.of(
                            new ModelEvent.ToolUseEmitted(call),
                            new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
                        List.of(
                            new ModelEvent.TextChunk("used the tool"),
                            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));

            @Override
            public ModelStream stream(ModelRequest request) {
              Iterator<ModelEvent> events = turns.removeFirst().iterator();
              return new ModelStream() {
                @Override
                public Iterator<ModelEvent> iterator() {
                  return events;
                }

                @Override
                public void close() {
                  // intentionally empty: this fake stream holds no resources to release
                }
              };
            }

            @Override
            public Set<Capability> capabilities() {
              return Set.of();
            }
          };
      AtomicBoolean consulted = new AtomicBoolean(false);
      Approver recording =
          new Approver() {
            @Override
            public Awaited<Decision> approve(ApprovalRequest request) {
              consulted.set(true);
              return Awaited.ready(Decision.allow());
            }
          };
      ToolGrant grant = ToolGrant.grant(new NoOpTool(), UsagePolicy.requireApproval());
      Agent<Nothing> agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  Nothing.class,
                  a -> a.name("scribe").model("fake-model").approver(recording).tools(grant));

      agent.converse().tell(new Nothing());

      assertThat(consulted).isTrue();
    }

    @Test
    void the_termination_override_is_consulted_by_the_reducer() {
      FakeProvider provider = new FakeProvider("hi");
      AtomicBoolean consulted = new AtomicBoolean(false);
      TerminationPolicy recording =
          state -> {
            consulted.set(true);
            return Optional.empty();
          };
      Agent<String> agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(a -> a.name("scribe").model("fake-model").termination(recording));

      agent.converse().tell("hi");

      assertThat(consulted).isTrue();
    }

    @Test
    void a_declared_context_window_reaches_model_settings_without_throwing() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a -> a.name("scribe").model("fake-model").contextWindow(9_000).maxTokens(1_000));

      RunOutcome reply = agent.converse().tell("hi");

      assertThat(RunOutcomes.failed(reply)).isFalse();
    }

    @Test
    void listenAsync_with_no_error_handler_never_vetoes_and_still_runs()
        throws InterruptedException {
      FakeProvider provider = new FakeProvider("hi");
      CountDownLatch handled = new CountDownLatch(1);
      Agent<String> agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .listenAsync(
                              ConversationEvent.class,
                              e -> {
                                handled.countDown();
                                throw new IllegalStateException("async listener blew up");
                              }));

      RunOutcome reply = agent.converse().tell("hi");

      assertThat(RunOutcomes.failed(reply)).isFalse();
      assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void the_renderer_override_replaces_the_vocabulary_driven_default() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<Nothing> agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  Nothing.class,
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .renderer(input -> List.of(new TextBlock("custom-render"))));

      agent.converse().tell(new Nothing());

      var context = provider.requests().getFirst().context();
      var block = (TextBlock) context.messages().getLast().content().getFirst();
      assertThat(block.text()).isEqualTo("custom-render");
    }
  }

  @Nested
  class Memory_downgrade_warning {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void wires_a_capturing_appender_onto_the_agent_builder_logger() {
      logger = (Logger) LoggerFactory.getLogger(AgentConfig.class);
      originalLevel = logger.getLevel();
      logger.setLevel(Level.WARN);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void unwires_the_appender_and_restores_the_loggers_level() {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
    }

    /**
     * Only the WARN events — the guard's own voice. The appender hears the whole logger category,
     * and another test's async listener can blow up on its executor thread AFTER its test ends,
     * landing an unrelated ERROR here mid-capture (the flake CI caught on 2026-08-15). Filtering to
     * WARN keeps every assertion about exactly the guard, immune to cross-test log bleed.
     */
    private java.util.List<ILoggingEvent> warnings() {
      return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void memory_defaulted_with_an_explicitly_configured_store_warns_about_the_downgrade() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider).store(ConversationStore.inMemory()))
          .agent(a -> a.name("scribe").model("fake-model"));

      assertThat(warnings()).hasSize(1);
      ILoggingEvent event = warnings().getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage())
          .contains("memory")
          .contains("restarts")
          .contains(".memory(");
    }

    @Test
    void an_explicitly_declared_memory_stays_silent_even_with_a_configured_store() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider).store(ConversationStore.inMemory()))
          .agent(
              a ->
                  a.name("scribe")
                      .model("fake-model")
                      .memory(Memory.pipeline(Transcript.inMemory()).build()));

      assertThat(warnings()).isEmpty();
    }

    @Test
    void a_defaulted_store_alongside_defaulted_memory_stays_silent() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider)).agent(a -> a.name("scribe").model("fake-model"));

      assertThat(warnings()).isEmpty();
    }

    @Test
    void an_explicitly_declared_memory_stays_silent_even_with_a_defaulted_store() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider))
          .agent(
              a ->
                  a.name("scribe")
                      .model("fake-model")
                      .memory(Memory.pipeline(Transcript.inMemory()).build()));

      assertThat(warnings()).isEmpty();
    }
  }

  /**
   * S2 (task-2-review.md, task-2 fix round 2): the agent-level warning {@code
   * SubagentAssembly.build()} logs when a subagent is declared against a harness whose own store
   * was explicitly configured but whose {@code subagentLinks} was left on the in-memory default —
   * the durability gap where a child settling after a restart leaves its parent parked forever with
   * nothing logged anywhere. Distinct from {@link HarnessConfigTest.Parks_downgrade_warning}: that
   * one fires from {@code HarnessConfig} whenever a durable store is configured, regardless of
   * subagents; this one fires from {@code AgentConfig} only when a subagent is actually declared —
   * {@code AgentConfig.build()} is the only place both facts (a durable store, and at least one
   * {@code .subagent(...)}) are known together.
   */
  @Nested
  class Subagent_links_warning {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void wires_a_capturing_appender_onto_the_agent_builder_logger() {
      logger = (Logger) LoggerFactory.getLogger(AgentConfig.class);
      originalLevel = logger.getLevel();
      logger.setLevel(Level.WARN);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void unwires_the_appender_and_restores_the_loggers_level() {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
    }

    /**
     * Only the WARN events — the guard's own voice. The appender hears the whole logger category,
     * and another test's async listener can blow up on its executor thread AFTER its test ends,
     * landing an unrelated ERROR here mid-capture (the flake CI caught on 2026-08-15). Filtering to
     * WARN keeps every assertion about exactly the guard, immune to cross-test log bleed.
     */
    private java.util.List<ILoggingEvent> warnings() {
      return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void
        a_subagent_declared_against_a_configured_store_with_defaulted_links_warns_about_the_downgrade() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider).store(ConversationStore.inMemory()))
          .agent(
              a ->
                  a.name("writer")
                      .model("fake-model")
                      // Explicit memory on both agents, isolating this guard from AgentConfig's
                      // own unrelated memory-downgrade warning (Memory_downgrade_warning above) —
                      // both share this same logger, and a store configured with no memory
                      // declared would otherwise warn twice (once per agent this harness builds)
                      // and pollute this assertion.
                      .memory(Memory.pipeline(Transcript.inMemory()).build())
                      .subagent(
                          sub ->
                              sub.name("researcher")
                                  .description("delegates research")
                                  .model("fake-model")
                                  .memory(Memory.pipeline(Transcript.inMemory()).build())));

      assertThat(warnings()).hasSize(1);
      ILoggingEvent event = warnings().getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("subagentLinks").contains(".subagentLinks(");
    }

    @Test
    void
        an_explicitly_configured_subagent_links_store_stays_silent_even_with_a_configured_store_and_a_subagent() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(
              h ->
                  h.provider(provider)
                      .store(ConversationStore.inMemory())
                      .subagentLinks(SubagentLinks.inMemory()))
          .agent(
              a ->
                  a.name("writer")
                      .model("fake-model")
                      .memory(Memory.pipeline(Transcript.inMemory()).build())
                      .subagent(
                          sub ->
                              sub.name("researcher")
                                  .description("delegates research")
                                  .model("fake-model")
                                  .memory(Memory.pipeline(Transcript.inMemory()).build())));

      assertThat(warnings()).isEmpty();
    }

    @Test
    void no_subagent_declared_stays_silent_even_with_a_configured_store_and_defaulted_links() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider).store(ConversationStore.inMemory()))
          .agent(
              a ->
                  a.name("writer")
                      .model("fake-model")
                      .memory(Memory.pipeline(Transcript.inMemory()).build()));

      assertThat(warnings()).isEmpty();
    }

    @Test
    void a_subagent_declared_against_a_defaulted_store_stays_silent() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider))
          .agent(
              a ->
                  a.name("writer")
                      .model("fake-model")
                      .subagent(
                          sub ->
                              sub.name("researcher")
                                  .description("delegates research")
                                  .model("fake-model")));

      assertThat(warnings()).isEmpty();
    }
  }

  @Nested
  class Approver_warning {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void wires_a_capturing_appender_onto_the_agent_builder_logger() {
      logger = (Logger) LoggerFactory.getLogger(AgentConfig.class);
      originalLevel = logger.getLevel();
      logger.setLevel(Level.WARN);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void unwires_the_appender_and_restores_the_loggers_level() {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
    }

    /**
     * Only the WARN events — the guard's own voice. The appender hears the whole logger category,
     * and another test's async listener can blow up on its executor thread AFTER its test ends,
     * landing an unrelated ERROR here mid-capture (the flake CI caught on 2026-08-15). Filtering to
     * WARN keeps every assertion about exactly the guard, immune to cross-test log bleed.
     */
    private java.util.List<ILoggingEvent> warnings() {
      return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void every_grant_using_the_canonical_allow_singleton_stays_silent_with_no_approver() {
      FakeProvider provider = new FakeProvider("hi");
      ToolGrant grant = ToolGrant.grant(new NoOpTool(), UsagePolicy.allow());

      Nessy.harness(h -> h.provider(provider))
          .agent(Nothing.class, a -> a.name("scribe").model("fake-model").tools(grant));

      assertThat(warnings()).isEmpty();
    }

    @Test
    void no_grants_at_all_stays_silent_with_no_approver() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider)).agent(a -> a.name("scribe").model("fake-model"));

      assertThat(warnings()).isEmpty();
    }

    @Test
    void a_custom_policy_grant_with_no_approver_still_warns() {
      FakeProvider provider = new FakeProvider("hi");
      ToolGrant grant = ToolGrant.grant(new NoOpTool(), UsagePolicy.requireApproval());

      Nessy.harness(h -> h.provider(provider))
          .agent(Nothing.class, a -> a.name("scribe").model("fake-model").tools(grant));

      assertThat(warnings()).hasSize(1);
      assertThat(warnings().getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void a_declared_approver_stays_silent_regardless_of_the_grants() {
      FakeProvider provider = new FakeProvider("hi");
      ToolGrant grant = ToolGrant.grant(new NoOpTool(), UsagePolicy.requireApproval());
      Approver approver =
          new Approver() {
            @Override
            public Awaited<Decision> approve(ApprovalRequest request) {
              return Awaited.ready(Decision.allow());
            }
          };

      Nessy.harness(h -> h.provider(provider))
          .agent(
              Nothing.class,
              a -> a.name("scribe").model("fake-model").tools(grant).approver(approver));

      assertThat(warnings()).isEmpty();
    }
  }

  @Nested
  class Missing_model {

    @Test
    void neither_model_declared_names_both_ways_to_supply_one() {
      var builder =
          new AgentConfig<>(
                  Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text())
              .name("scribe");

      assertThatThrownBy(builder::build)
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("model(")
          .hasMessageContaining("defaultModel(");
    }
  }

  @Nested
  class Name {

    @Test
    void build_without_a_name_refuses_with_the_covenant() {
      var builder =
          new AgentConfig<>(
                  Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text())
              .model("fake-model");

      assertThatThrownBy(builder::build)
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("name")
          .hasMessageContaining("parked work")
          .hasMessageContaining("restarts");
    }

    @Test
    void a_blank_name_is_rejected_at_the_setter_with_the_covenant() {
      var builder =
          new AgentConfig<>(
              Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text());

      assertThatThrownBy(() -> builder.name("   "))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("name")
          .hasMessageContaining("parked work")
          .hasMessageContaining("restarts");
    }

    @Test
    void a_null_name_is_rejected_at_the_setter_with_the_covenant_the_same_way_as_blank() {
      var builder =
          new AgentConfig<>(
              Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text());

      assertThatThrownBy(() -> builder.name(null))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("name")
          .hasMessageContaining("parked work")
          .hasMessageContaining("restarts");
    }
  }

  @Nested
  class Null_rejection {

    @Test
    void a_null_approver_is_rejected() {
      var builder =
          new AgentConfig<>(
                  Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text())
              .name("scribe");

      assertThatThrownBy(() -> builder.approver(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("approver");
    }

    @Test
    void a_null_termination_policy_is_rejected() {
      var builder =
          new AgentConfig<>(
                  Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text())
              .name("scribe");

      assertThatThrownBy(() -> builder.termination(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("termination");
    }

    @Test
    void a_null_system_prompt_is_rejected() {
      var builder =
          new AgentConfig<>(
                  Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text())
              .name("scribe");

      assertThatThrownBy(() -> builder.systemPrompt(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("systemPrompt");
    }
  }
}
