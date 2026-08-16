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
package org.jwcarman.nessy.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.ObservationRegistry;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.AgentConfigurationException;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.InboxEntry;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;
import org.jwcarman.nessy.testing.ScriptedModelProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link NessyAutoConfiguration} against a runner combining all three substrate autoconfigurations
 * (both providers, persistence), the same trio {@link Harness} weaves together in a real
 * application. Each test isolates one seam: a provider alone is enough for a harness on defaults; a
 * store bean, an {@link ObservationRegistry} bean, and {@code nessy.default-model} are each woven
 * in when present; a user-declared {@link Harness} always wins outright.
 */
class NessyAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AnthropicProviderAutoConfiguration.class,
                  OpenAiProviderAutoConfiguration.class,
                  JdbcPersistenceAutoConfiguration.class,
                  NessyAutoConfiguration.class))
          .withPropertyValues("nessy.anthropic.api-key=test-key");

  @Test
  void a_provider_alone_yields_a_harness_on_defaults() {
    runner.run(context -> assertThat(context).hasSingleBean(Harness.class));
  }

  @Test
  void a_store_bean_is_woven_in() {
    // A real Task-2 JDBC store needs a real Postgres connection to query without throwing, and
    // the module's own JDBC-backed tests are Testcontainers-based and tagged "container" for
    // exactly that reason — out of scope for this offline context runner). So the observable seam
    // here is a hand-instrumented ConversationStore, standing in for "the Task 2 store bean," that
    // records whether the woven harness actually reaches it: a broken wiring would leave the
    // probe untouched (or throw), not merely return empty from an unrelated in-memory default.
    // Task-4: {@code peek} no longer touches the store at all (it reads the harness's own {@code
    // Parks} registry instead), so the probe is read through {@link Agent#snapshot}'s own {@code
    // store.load} instead — the same store-wiring question, a different call that still asks it.
    var probe = new ProbeConversationStore();
    runner
        .withBean("mine", ConversationStore.class, () -> probe)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Harness.class);
              Harness harness = context.getBean(Harness.class);
              Agent<String> agent = harness.agent(a -> a.name("probe").model("probe-model"));
              assertThat(probe.loaded()).isFalse();
              agent.snapshot(ConversationId.generate());
              assertThat(probe.loaded()).isTrue();
            });
  }

  @Test
  void a_parks_bean_is_woven_in() {
    // Harness#parks is package-private with no public accessor, so the proof goes through
    // Agent#peek: a token this exact Parks bean instance already knows about must come back
    // from an agent built off the woven harness, which it can only do if the harness reached
    // this instance rather than defaulting to its own private Parks.inMemory().
    Parks mine = Parks.inMemory();
    ConversationId conversationId = ConversationId.generate();
    ParkToken token = new ParkToken("probe-token");
    ToolCall call = new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
    mine.park(new Parks.Park(conversationId, token, call, "probe"));
    runner
        .withBean("mine", Parks.class, () -> mine)
        .run(
            context -> {
              Harness harness = context.getBean(Harness.class);
              Agent<String> agent = harness.agent(a -> a.name("probe").model("probe-model"));
              assertThat(agent.peek(token)).isPresent();
              assertThat(agent.peek(token).orElseThrow().token()).isEqualTo(token);
            });
  }

  /**
   * Final review SF-3: before this bean existed, a Boot app with {@code nessy-jdbc} on the
   * classpath and a {@code .subagent(...)} declared got {@code SubagentLinks.inMemory()} regardless
   * of what {@code JdbcPersistenceAutoConfiguration} produced, because nothing in {@link
   * NessyAutoConfiguration} ever called {@code HarnessConfig.subagentLinks(...)}. {@code
   * Harness#subagentLinks()} is package-private, and — unlike {@link Parks}, which {@link
   * Agent#peek} reads directly — no public {@link Agent}/{@link org.jwcarman.nessy.Subagent} door
   * reads a {@link SubagentLinks} bean at all; it is purely {@code AgentTools}' own internal
   * park-recipe bookkeeping. So the proof has to go through the actual delegation-park flow that
   * touches it, the same probe technique {@link #a_store_bean_is_woven_in} uses for {@link
   * ConversationStore}: a hand-instrumented {@link SubagentLinks}, standing in for the woven bean,
   * records whether the harness's own internal machinery actually reaches it once a gated child
   * parks.
   */
  @Test
  void a_subagent_links_bean_is_woven_in() {
    var probe = new ProbeSubagentLinks();
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUse("d1", "researcher", JsonNodeFactory.instance.objectNode().put("task", "go"))
            .endWithToolUse()
            .toolUse("ask-1", "ask_question", JsonNodeFactory.instance.objectNode())
            .endWithToolUse()
            .build();
    ParkingApprover approver = new ParkingApprover();
    runner
        .withBean("provider", ModelProvider.class, () -> provider)
        .withBean("mine", SubagentLinks.class, () -> probe)
        .run(
            context -> {
              Harness harness = context.getBean(Harness.class);
              Agent<String> writer =
                  harness.agent(
                      a ->
                          a.name("writer")
                              .model("probe-model")
                              .approver(approver)
                              .subagent(
                                  sub ->
                                      sub.name("researcher")
                                          .description("delegates research")
                                          .model("probe-model")
                                          .tools(
                                              ToolGrant.grant(
                                                  new AskQuestionTool(),
                                                  UsagePolicy.requireApproval()))));
              assertThat(probe.saved()).isFalse();
              writer.converse().tell("investigate");
              assertThat(probe.saved()).isTrue();
            });
  }

  @Test
  void a_present_observation_registry_does_not_break_harness_assembly() {
    // ObservationRegistry#ifAvailable is a package-private wire-through with no public accessor
    // to reflect into, same limit NessyAutoConfigurationTest hits everywhere else in this class —
    // the cheap, non-reflective proof available offline is that a present registry bean doesn't
    // throw building the Harness. The registry's spans actually joining Boot's own HTTP/JDBC
    // trace is chat-web's Grafana story (its docker-compose observability stack), not something
    // an offline ApplicationContextRunner can observe.
    runner
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void nessy_default_model_reaches_the_harness() {
    // Harness#defaultModel() is package-private and Harness exposes no public accessor for it, so
    // the proof has to go through behavior instead of a getter: the agent factory requires a
    // model from somewhere (an explicit agent(a -> a.model(...)) call or the harness's own
    // defaultModel), throwing AgentConfigurationException otherwise. The negative case first —
    // no nessy.default-model, agent(a -> a.name(...)) fails — establishes that this really is the
    // discriminating signal, not something the factory would succeed at regardless.
    runner.run(
        context -> {
          Harness bare = context.getBean(Harness.class);
          assertThatThrownBy(() -> bare.agent(a -> a.name("probe")))
              .isInstanceOf(AgentConfigurationException.class);
        });
    runner
        .withPropertyValues("nessy.default-model=claude-haiku")
        .run(
            context -> {
              Harness harness = context.getBean(Harness.class);
              assertThat(harness.agent(a -> a.name("probe"))).isNotNull();
            });
  }

  @Test
  void a_user_declared_harness_wins() {
    Harness mine =
        Nessy.harness(
            h -> h.provider(ScriptedModelProvider.builder().text("hi").endTurn().build()));
    runner
        .withBean("mine", Harness.class, () -> mine)
        .run(context -> assertThat(context.getBean(Harness.class)).isSameAs(mine));
  }

  /**
   * Delegates every operation to a fresh {@link ConversationStore#inMemory()}, except {@link #load}
   * — the call {@link Agent#snapshot} makes — which is also recorded in {@link #loaded}, the
   * observable proof {@link #a_store_bean_is_woven_in} reads instead of reflecting into {@link
   * Harness}'s private field.
   */
  private static final class ProbeConversationStore implements ConversationStore {

    private final ConversationStore delegate = ConversationStore.inMemory();
    private final AtomicBoolean loaded = new AtomicBoolean();

    boolean loaded() {
      return loaded.get();
    }

    @Override
    public Optional<Loaded> load(ConversationId id) {
      loaded.set(true);
      return delegate.load(id);
    }

    @Override
    public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
      return delegate.save(state, drainedInboxIds);
    }

    @Override
    public void append(ConversationId id, InboxEntry entry) {
      delegate.append(id, entry);
    }
  }

  /**
   * Delegates every operation to a fresh {@link SubagentLinks#inMemory()}, except {@link #save} —
   * the call the internal subagent-tool machinery makes the moment a gated child parks — which is
   * also recorded in {@link #saved()}, the observable proof {@link
   * #a_subagent_links_bean_is_woven_in} reads instead of reflecting into {@link Harness}'s private
   * field.
   */
  private static final class ProbeSubagentLinks implements SubagentLinks {

    private final SubagentLinks delegate = SubagentLinks.inMemory();
    private final AtomicBoolean saved = new AtomicBoolean();

    boolean saved() {
      return saved.get();
    }

    @Override
    public Optional<ParkToken> find(ConversationId child) {
      return delegate.find(child);
    }

    @Override
    public void save(ConversationId child, ParkToken parentToken) {
      saved.set(true);
      delegate.save(child, parentToken);
    }

    @Override
    public void forget(ConversationId child) {
      delegate.forget(child);
    }
  }

  record AskInput(String question) {}

  /** A tool that always succeeds once invoked — the gate is what parks, not the tool itself. */
  private static final class AskQuestionTool implements Tool<AskInput> {

    @Override
    public String name() {
      return "ask_question";
    }

    @Override
    public String description() {
      return "Asks a clarifying question";
    }

    @Override
    public Class<AskInput> inputType() {
      return AskInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(AskInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("answered: " + input.question()));
    }
  }

  /** Parks the first call it is asked. */
  private static final class ParkingApprover implements Approver {

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      return Awaited.parked(ParkToken.generate());
    }
  }
}
