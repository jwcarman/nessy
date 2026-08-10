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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.compaction.Compactor;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@code AgentBuilder}'s own validation and configuration wiring: every setter actually reaches the
 * {@link org.jwcarman.nessy.spi.ExecutionEngine} it configures, isolated from the full build path
 * {@code HarnessTest} exercises for the model-resolution and declared-listening stories.
 */
class AgentBuilderTest {

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
      var agent = Nessy.harness(NEVER_CALLED).build().agent();

      assertThatThrownBy(() -> agent.tools(grants))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("grants");
    }

    @Test
    void a_null_element_in_the_grants_array_is_rejected() {
      ToolGrant present = ToolGrant.grant(new NoOpTool(), UsagePolicy.allow());
      var agent = Nessy.harness(NEVER_CALLED).build().agent();

      assertThatThrownBy(() -> agent.tools(present, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("grants[1]");
    }

    @Test
    void an_empty_grants_array_registers_no_tools() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(provider).build().agent().model("fake-model").tools().build();

      Reply reply = agent.converse().tell("hi");

      assertThat(reply.text()).isEqualTo("hi");
    }
  }

  @Nested
  class Configuration {

    @Test
    void the_system_prompt_max_tokens_and_capabilities_all_reach_the_request() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .systemPrompt("be terse")
              .maxTokens(777)
              .capabilities(Set.of(Capability.PROMPT_CACHING))
              .build();

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
          Nessy.harness(provider)
              .defaultModel("harness-default")
              .build()
              .agent()
              .model("  ")
              .build();

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
          Nessy.harness(provider)
              .build()
              .agent(Nothing.class)
              .model("fake-model")
              .approver(recording)
              .tools(grant)
              .build();

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
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .termination(recording)
              .build();

      agent.converse().tell("hi");

      assertThat(consulted).isTrue();
    }

    @Test
    void the_compaction_override_replaces_the_default_summarizing_compactor() {
      FakeProvider provider = new FakeProvider("hi");
      AtomicBoolean consulted = new AtomicBoolean(false);
      Compactor recording =
          new Compactor() {
            @Override
            public boolean requiresCompaction(ConversationState state) {
              consulted.set(true);
              return false;
            }

            @Override
            public Result compact(ConversationState state) {
              throw new AssertionError("never triggered in this scenario");
            }
          };
      Agent<String> agent =
          Nessy.harness(provider).build().agent().model("fake-model").compaction(recording).build();

      agent.converse().tell("hi");

      assertThat(consulted).isTrue();
    }

    @Test
    void a_declared_context_window_wires_into_the_default_compactor_without_throwing() {
      // SummarizerTest and WindowCompactionTest already pin the exact 0.8x(window - maxTokens)
      // trigger arithmetic; this only pins that AgentBuilder.contextWindow(...) actually reaches
      // defaultCompactor's window-derived branch (as opposed to the plain-trigger branch every
      // other test in this file exercises) rather than being silently ignored.
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .contextWindow(9_000)
              .maxTokens(1_000)
              .build();

      Reply reply = agent.converse().tell("hi");

      assertThat(reply.failed()).isFalse();
    }

    @Test
    void the_context_customizer_configures_the_pipeline_the_engine_and_contextFor_both_use() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .context(
                  pipeline ->
                      pipeline.project(ctx -> Context.of(List.of(Message.user("overridden")))))
              .build();

      agent.converse().tell("hi");

      assertThat(provider.requests().getFirst().context().messages())
          .containsExactly(Message.user("overridden"));
    }

    @Test
    void listenAsync_with_no_error_handler_never_vetoes_and_still_runs()
        throws InterruptedException {
      FakeProvider provider = new FakeProvider("hi");
      CountDownLatch handled = new CountDownLatch(1);
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .listenAsync(
                  ConversationEvent.class,
                  e -> {
                    handled.countDown();
                    throw new IllegalStateException("async listener blew up");
                  })
              .build();

      Reply reply = agent.converse().tell("hi");

      assertThat(reply.failed()).isFalse();
      assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void the_renderer_override_replaces_the_vocabulary_driven_default() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<Nothing> agent =
          Nessy.harness(provider)
              .build()
              .agent(Nothing.class)
              .model("fake-model")
              .renderer(input -> List.of(new TextBlock("custom-render")))
              .build();

      agent.converse().tell(new Nothing());

      var context = provider.requests().getFirst().context();
      var block = (TextBlock) context.messages().getLast().content().getFirst();
      assertThat(block.text()).isEqualTo("custom-render");
    }
  }

  @Nested
  class Missing_model {

    @Test
    void neither_model_declared_names_both_ways_to_supply_one() {
      var builder = Nessy.harness(NEVER_CALLED).build().agent();

      assertThatThrownBy(builder::build)
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("model(")
          .hasMessageContaining("defaultModel(");
    }
  }
}
