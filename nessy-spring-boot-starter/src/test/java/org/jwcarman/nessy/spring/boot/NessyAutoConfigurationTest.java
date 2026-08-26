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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.ApprovalDesk;
import org.jwcarman.nessy.agent.CompletionDesk;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.testing.ScriptedModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The starter with no database in sight: a {@code Model} bean, a system prompt, and nothing else —
 * and a whole harness comes out, on the in-memory stores.
 *
 * <p>Every assertion here is about WIRING, which is the only thing an auto-configuration is: which
 * beans exist, which conditions chose them, and whose bean wins when the application declares its
 * own. No model provider is ever reached and no database is ever touched.
 */
class NessyAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
          .withUserConfiguration(ScriptedModelConfiguration.class)
          .withPropertyValues("nessy.system-prompt=you are a test harness");

  @Nested
  class WithNoDataSource {

    @Test
    void the_harness_and_both_desks_are_beans() {
      runner.run(
          context ->
              assertThat(context)
                  .hasSingleBean(Harness.class)
                  .hasSingleBean(ApprovalDesk.class)
                  .hasSingleBean(CompletionDesk.class));
    }

    @Test
    void the_stores_are_the_in_memory_pair() {
      runner.run(
          context -> {
            assertThat(context).hasSingleBean(Substrate.class).hasSingleBean(Continuum.class);
            assertThat(context.getBean(Substrate.class)).isInstanceOf(InMemorySubstrate.class);
          });
    }

    @Test
    void the_agent_type_and_backlog_capacity_come_from_the_properties() {
      runner
          .withPropertyValues("nessy.type=watchman", "nessy.backlog-capacity=7")
          .run(
              context -> {
                assertThat(context.getBean(Harness.class).type().name()).isEqualTo("watchman");
                assertThat(context.getBean(NessyProperties.class).backlogCapacity()).isEqualTo(7);
              });
    }

    @Test
    void the_defaults_are_the_harness_defaults() {
      runner.run(
          context -> {
            NessyProperties properties = context.getBean(NessyProperties.class);
            assertThat(properties.type()).isEqualTo("agent");
            assertThat(properties.backlogCapacity()).isEqualTo(1024);
            assertThat(properties.staleness()).isEqualTo(Duration.ofMinutes(5));
          });
    }

    @Test
    void a_missing_system_prompt_fails_the_context_loudly() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
          .withUserConfiguration(ScriptedModelConfiguration.class)
          .run(
              context ->
                  assertThat(context)
                      .hasFailed()
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining("nessy.system-prompt"));
    }
  }

  @Nested
  class TheApplicationsOwnBeansWin {

    @Test
    void a_user_supplied_substrate_replaces_the_default() {
      Substrate mine = new InMemorySubstrate();
      runner
          .withBean(Substrate.class, () -> mine)
          .run(context -> assertThat(context.getBean(Substrate.class)).isSameAs(mine));
    }

    @Test
    void a_user_supplied_model_is_never_replaced_by_discovery() {
      runner.run(
          context -> assertThat(context.getBean(Model.class)).isInstanceOf(ScriptedModel.class));
    }

    @Test
    void a_user_supplied_harness_replaces_the_whole_recipe() {
      runner
          .withUserConfiguration(OwnHarnessConfiguration.class)
          .run(
              context ->
                  assertThat(context.getBean(Harness.class).type().name()).isEqualTo("hand-wired"));
    }
  }

  @Nested
  class Grants {

    @Test
    void a_bare_tool_bean_is_granted_an_allowing_approver() {
      Tool<Note> bare = tool("bare");

      List<ToolGrant> grants = NessyAutoConfiguration.grants(List.of(bare), List.of());

      assertThat(grants).isNotEmpty();
      assertThat(grants)
          .singleElement()
          .satisfies(
              grant -> {
                assertThat(grant.tool().name()).isEqualTo("bare");
                assertThat(grant.approver()).isSameAs(Approvers.allow());
              });
    }

    @Test
    void a_grant_bean_is_taken_exactly_as_it_was_declared() {
      ToolGrant declared = ToolGrant.grant(tool("declared"), Approvers.defer());

      List<ToolGrant> grants = NessyAutoConfiguration.grants(List.of(), List.of(declared));

      assertThat(grants).isNotEmpty();
      assertThat(grants).singleElement().isSameAs(declared);
    }

    @Test
    void both_kinds_of_bean_arrive_together() {
      ToolGrant declared = ToolGrant.grant(tool("declared"), Approvers.defer());

      List<ToolGrant> grants =
          NessyAutoConfiguration.grants(List.of(tool("bare")), List.of(declared));

      assertThat(grants).isNotEmpty();
      assertThat(grants)
          .extracting(grant -> grant.tool().name())
          .containsExactlyInAnyOrder("bare", "declared");
    }
  }

  @Nested
  class TheFactStream {

    @Test
    void a_user_supplied_observer_is_subscribed_alongside_the_starters_own() {
      runner
          .withUserConfiguration(CountingObserverConfiguration.class)
          .run(
              context -> {
                CountingObserver observer = context.getBean(CountingObserver.class);
                context.getBean(Teller.class).tell("hello");
                await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(observer.applied()).isPositive());
              });
    }
  }

  private static Tool<Note> tool(String name) {
    return Tool.of(
        Note.class, t -> t.name(name).description("a tool named " + name).executes(note -> "ok"));
  }

  @Configuration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {

    @Bean
    Model model() {
      return ScriptedModel.script(s -> s.text("nothing to do").endTurn());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnHarnessConfiguration {

    @Bean
    Harness<String> harness(Model model) {
      return Nessy.harness(c -> c.type("hand-wired").model(model).systemPrompt("mine"));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CountingObserverConfiguration {

    @Bean
    CountingObserver countingObserver() {
      return new CountingObserver();
    }

    /**
     * Injected as {@code Harness<String>}, which is the point: this proves Spring resolves the
     * starter's harness bean by its full generic type, the way {@code nessy-examples/watchman} and
     * every other consumer will inject it — and it does so without a cast, which a raw {@code
     * context.getBean(Harness.class)} lookup could not.
     */
    @Bean
    Teller teller(Harness<String> harness) {
      return new Teller(harness);
    }
  }

  record Teller(Harness<String> harness) {

    void tell(String observation) {
      harness.bind(AgentId.of("scope")).tell(observation);
    }
  }

  record Note(String text) {}
}
