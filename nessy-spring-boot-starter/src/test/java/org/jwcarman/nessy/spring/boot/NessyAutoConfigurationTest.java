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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.jdbc.JdbcContinuumRepository;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.ApprovalDesk;
import org.jwcarman.nessy.agent.CompletionDesk;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.model.discovery.ModelDiscovery;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate;
import org.jwcarman.nessy.testing.ScriptedModel;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
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
    void nothing_projects_pending_approvals_without_a_database() {
      runner.run(
          context ->
              assertThat(context)
                  .doesNotHaveBean(PendingApprovals.class)
                  .doesNotHaveBean(PendingApprovalsRepository.class));
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

    /**
     * {@code nessy.capabilities} reaches {@code ModelSettings.capabilities} — a REQUEST to the
     * provider, which is why the assertion is on the request the model actually received rather
     * than on the property. Relaxed binding turns {@code prompt-caching} into {@code
     * PROMPT_CACHING}, which is the shape a YAML file wants to write.
     */
    @Test
    void requested_capabilities_reach_the_model_request() {
      ScriptedModel model = ScriptedModel.script(s -> s.text("nothing to do").endTurn());
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
          .withPropertyValues(
              "nessy.system-prompt=you are a test harness", "nessy.capabilities=prompt-caching")
          .withBean(Model.class, () -> model)
          .withUserConfiguration(TellerConfiguration.class)
          .run(
              context -> {
                assertThat(context.getBean(NessyProperties.class).capabilities())
                    .containsExactly(Capability.PROMPT_CACHING);
                context.getBean(Teller.class).tell("hello");
                await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                        () ->
                            assertThat(model.requests())
                                .isNotEmpty()
                                .allSatisfy(
                                    request ->
                                        assertThat(request.requested())
                                            .containsExactly(Capability.PROMPT_CACHING)));
              });
    }

    @Test
    void no_capabilities_are_requested_by_default() {
      runner.run(
          context -> assertThat(context.getBean(NessyProperties.class).capabilities()).isEmpty());
    }

    /**
     * The gateway discovery built is a bean with a destroy method, so the container closes its SDK
     * client on shutdown — but ONLY when discovery ran at all. An application declaring its own
     * {@code Model} owns whatever built it, and discovery reaching for credentials it deliberately
     * did not supply would fail the context outright.
     */
    @Test
    void a_user_supplied_model_means_discovery_never_runs() {
      runner.run(
          context ->
              assertThat(context).hasNotFailed().doesNotHaveBean(ModelDiscovery.Selection.class));
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

    /**
     * Two sources for one prompt is a configuration mistake, not a preference to resolve silently
     * (Task 1 review, finding #6 — this branch was the untested one).
     */
    @Test
    void setting_both_system_prompt_properties_fails_the_context_loudly() {
      runner
          .withPropertyValues("nessy.system-prompt-file=classpath:system-prompt.txt")
          .run(
              context ->
                  assertThat(context)
                      .hasFailed()
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining(
                          "set either nessy.system-prompt or nessy.system-prompt-file, not both"));
    }

    @Test
    void the_system_prompt_can_come_from_a_file() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
          .withUserConfiguration(ScriptedModelConfiguration.class)
          .withPropertyValues("nessy.system-prompt-file=classpath:system-prompt.txt")
          .run(context -> assertThat(context).hasNotFailed().hasSingleBean(Harness.class));
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

  /**
   * A {@code DataSource} bean is the whole switch (spec §1.1): it turns the stores durable and it
   * turns the projection on. No database is contacted here — none of these beans opens a connection
   * to be constructed, which is exactly why this can stay a wiring test.
   */
  @Nested
  class WithADataSource {

    private final ApplicationContextRunner durable =
        runner.withBean(DataSource.class, NessyAutoConfigurationTest::unreachableDataSource);

    @Test
    void the_stores_become_the_jdbc_pair() {
      durable.run(
          context ->
              assertThat(context.getBean(Substrate.class)).isInstanceOf(JdbcSubstrate.class));
    }

    @Test
    void the_projection_and_its_read_door_appear() {
      durable.run(
          context ->
              assertThat(context)
                  .hasSingleBean(PendingApprovals.class)
                  .hasSingleBean(PendingApprovalsRepository.class));
    }

    /**
     * The Continuum has to go durable too, not just the substrate: the two stores are a pair, and a
     * JDBC substrate over an in-memory computation store drops every delivery silently.
     */
    @Test
    void the_continuum_becomes_a_jdbc_backed_one_alongside_the_substrate() {
      durable.run(
          context -> {
            assertThat(context.getBean(Substrate.class)).isInstanceOf(JdbcSubstrate.class);
            assertThat(context.getBean(Continuum.class))
                .extracting("repository")
                .isInstanceOf(JdbcContinuumRepository.class);
          });
    }

    @Test
    void the_projection_is_subscribed_to_the_fact_stream_like_any_other_observer() {
      durable.run(
          context ->
              assertThat(context.getBeanProvider(HarnessObserver.class).orderedStream().toList())
                  .isNotEmpty()
                  .hasAtLeastOneElementOfType(PendingApprovals.class));
    }

    /**
     * The additive claim, where there is actually something to be additive with: the projection and
     * an application's own observer are BOTH on the stream, neither displacing the other. No fold
     * happens here — this is bean wiring, so the unreachable DataSource is never opened.
     */
    @Test
    void every_observer_bean_is_subscribed_together() {
      durable
          .withUserConfiguration(CountingObserverConfiguration.class)
          .run(
              context ->
                  assertThat(
                          context.getBeanProvider(HarnessObserver.class).orderedStream().toList())
                      .hasAtLeastOneElementOfType(PendingApprovals.class)
                      .hasAtLeastOneElementOfType(CountingObserver.class));
    }
  }

  /**
   * The both-or-neither rule (Task 1 review, finding #4). The starter cannot wire a mixed pair on
   * its own, but an application supplying exactly ONE of the two stores while a {@code DataSource}
   * is present gets the other one JDBC-backed — a durable store over a volatile one, whichever way
   * round, and both directions lose work in silence. Startup is where that gets said.
   */
  @Nested
  class MixedDurabilityIsRefused {

    private final ApplicationContextRunner durable =
        runner.withBean(DataSource.class, NessyAutoConfigurationTest::unreachableDataSource);

    @Test
    void a_user_supplied_substrate_beside_a_data_source_fails_the_context() {
      durable
          .withBean(Substrate.class, InMemorySubstrate::new)
          .run(
              context ->
                  assertThat(context)
                      .hasFailed()
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining("Mixed durability")
                      .hasMessageContaining("BOTH durable or BOTH volatile"));
    }

    @Test
    void a_user_supplied_continuum_beside_a_data_source_fails_the_context() {
      durable
          .withBean(
              Continuum.class,
              () -> new DefaultContinuum(new InMemoryContinuumRepository(), InstantSource.system()))
          .run(
              context ->
                  assertThat(context)
                      .hasFailed()
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining("Mixed durability")
                      .hasMessageContaining("BOTH durable or BOTH volatile"));
    }

    @Test
    void supplying_both_stores_is_the_applications_own_business() {
      durable
          .withBean(Substrate.class, InMemorySubstrate::new)
          .withBean(
              Continuum.class,
              () -> new DefaultContinuum(new InMemoryContinuumRepository(), InstantSource.system()))
          .run(context -> assertThat(context).hasNotFailed().hasSingleBean(Harness.class));
    }

    /** No DataSource, no mismatch: both starter beans are volatile, so one of the user's pairs. */
    @Test
    void one_user_supplied_store_without_a_data_source_is_fine() {
      runner
          .withBean(Substrate.class, InMemorySubstrate::new)
          .run(context -> assertThat(context).hasNotFailed().hasSingleBean(Harness.class));
    }
  }

  /**
   * The DEFAULT path — no {@link Model} bean, so the starter's own discovery runs. It had no test
   * at all until the fix round, and it was BROKEN for one commit: {@code nessyModel} was declared
   * before {@code nessyModelSelection}, so the selection's {@code @ConditionalOnMissingBean(Model
   * .class)} matched {@code nessyModel}'s own already-registered definition, backed off, and left
   * {@code nessyModel} asking for a bean nobody declared. Every application that did not supply its
   * own model — which is the default, and the soak — failed with "No qualifying bean of type
   * ModelDiscovery$Selection". These two tests are the ones that would have caught it.
   */
  @Nested
  class TheDefaultPathThroughDiscovery {

    private final ApplicationContextRunner discovering =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
            .withPropertyValues("nessy.system-prompt=you are a test harness");

    /**
     * With no credentials, the context must fail with DISCOVERY's own complaint — which proves
     * discovery ran at all. A wiring failure here ("No qualifying bean of type ...Selection") is
     * the regression: it means the selection bean backed off and nothing reached the environment.
     */
    @Test
    void without_credentials_the_failure_is_discoverys_own_not_a_wiring_one() {
      discovering.run(
          context -> {
            assertThat(context).hasFailed();
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no model provider credentials found")
                .hasMessageContaining(StarterTestBootstrap.PROVIDER_NAME);
            assertThat(context)
                .getFailure()
                .hasMessageNotContaining("No qualifying bean of type")
                .hasMessageNotContaining("required a bean of type");
          });
    }

    /**
     * With a bootstrap that DOES supply, a whole harness comes out and its model is discovery's.
     */
    @Test
    void with_credentials_the_harness_is_built_from_the_discovered_model() throws Exception {
      try (AutoCloseable supplying = StarterTestBootstrap.supplying()) {
        assertThat(supplying).isNotNull();
        discovering.run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(Harness.class);
              assertThat(context).hasSingleBean(ModelDiscovery.Selection.class);
              assertThat(context.getBean(ModelDiscovery.Selection.class).providerName())
                  .isEqualTo(StarterTestBootstrap.PROVIDER_NAME);
              assertThat(context.getBean(Model.class).id())
                  .isEqualTo(StarterTestBootstrap.MODEL_ID);
              assertThat(context.getBean(Model.class))
                  .isSameAs(context.getBean(ModelDiscovery.Selection.class).model());
            });
      }
    }

    /**
     * And the container releases the gateway on context close, which is the whole point of
     * registering the selection with {@code destroyMethod = "close"}.
     */
    @Test
    void closing_the_context_closes_the_discovered_gateway() throws Exception {
      try (AutoCloseable supplying = StarterTestBootstrap.supplying()) {
        assertThat(supplying).isNotNull();
        var gateway = new AtomicReference<ModelProvider>();
        discovering.run(
            context -> {
              gateway.set(context.getBean(ModelDiscovery.Selection.class).provider());
              assertThat(gateway.get())
                  .isInstanceOfSatisfying(
                      StarterTestBootstrap.TestGateway.class,
                      open -> assertThat(open.isClosed()).isFalse());
            });
        // The runner closes the context when run(...) returns.
        assertThat((StarterTestBootstrap.TestGateway) gateway.get())
            .satisfies(closed -> assertThat(closed.isClosed()).isTrue());
      }
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

  /**
   * A {@code Tool<X>} bean reaching the harness, proven through a REAL context rather than a direct
   * call to {@link NessyAutoConfiguration#grants}. Two things could break and neither shows up in
   * that unit test: Spring resolving {@code ObjectProvider<Tool<?>>} against a bean method declared
   * as the concrete {@code Tool<Note>} — the shape every application writes — and the grant
   * actually reaching the registry the executor looks calls up in. So the model is scripted to CALL
   * the tool, and the tool's own side effect is the proof: an unregistered name never runs.
   */
  @Nested
  class AToolBean {

    @Test
    void a_tool_bean_is_registered_on_the_harness_and_runs_when_the_model_calls_it() {
      RecordingNoteTool note = new RecordingNoteTool();
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
          .withPropertyValues("nessy.system-prompt=you are a test harness")
          .withBean(Model.class, NessyAutoConfigurationTest::callsTheNoteTool)
          .withUserConfiguration(TellerConfiguration.class)
          .withBean(NoteToolConfiguration.class, () -> new NoteToolConfiguration(note))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                context.getBean(Teller.class).tell("write a note");
                await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(note.written()).containsExactly("hello"));
              });
    }
  }

  /**
   * The half-classpath hole (final review, finding #2). Both JDBC adapters are optional
   * dependencies, so exactly one of them is an easy classpath to end up with — and before this
   * guard it was the worst possible outcome, reached in silence: {@code DurableStores} needs both
   * classes and {@code VolatileStores} needs both absent, so NEITHER matched, no store bean
   * existed, {@code requireMatchedDurability} read the absence as "the application wired both
   * itself", and the harness fell back to in-memory stores with a live {@code DataSource} in the
   * context.
   *
   * <p>{@code FilteredClassLoader} is what makes this testable at all: it is the only way to ask
   * "what happens when this jar is missing?" from inside a build where both jars are present.
   */
  @Nested
  class AHalfDurableClasspath {

    private ApplicationContextRunner withoutJdbc(Class<?>... hidden) {
      return new ApplicationContextRunner()
          .withClassLoader(new FilteredClassLoader(hidden))
          .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
          .withPropertyValues("nessy.type=ops", "nessy.system-prompt=you restart things")
          .withUserConfiguration(ScriptedModelConfiguration.class)
          .withBean(DataSource.class, NessyAutoConfigurationTest::unreachableDataSource);
    }

    @Test
    void refuses_to_start_when_only_the_substrate_adapter_is_present() {
      withoutJdbc(JdbcContinuumRepository.class)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context)
                    .getFailure()
                    .hasStackTraceContaining("Half a durable classpath")
                    .hasStackTraceContaining("org.jwcarman.continuum:continuum-jdbc");
              });
    }

    @Test
    void refuses_to_start_when_only_the_continuum_adapter_is_present() {
      withoutJdbc(JdbcSubstrate.class)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context)
                    .getFailure()
                    .hasStackTraceContaining("Half a durable classpath")
                    .hasStackTraceContaining("org.jwcarman.nessy:nessy-substrate-jdbc");
              });
    }

    /**
     * Neither adapter is the volatile pair, which is honest and must keep working: an application
     * with a {@code DataSource} for its own tables and no interest in durable agents is not making
     * a mistake.
     */
    @Test
    void neither_adapter_is_still_the_honest_in_memory_pair() {
      withoutJdbc(JdbcSubstrate.class, JdbcContinuumRepository.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(Substrate.class)).isInstanceOf(InMemorySubstrate.class);
              });
    }

    /**
     * An application that declares both stores itself owes the starter nothing, classpath or not.
     */
    @Test
    void an_application_that_wired_both_stores_itself_is_left_alone() {
      Substrate mine = new InMemorySubstrate();
      withoutJdbc(JdbcContinuumRepository.class)
          .withBean(Substrate.class, () -> mine)
          .withBean(
              Continuum.class,
              () -> new DefaultContinuum(new InMemoryContinuumRepository(), InstantSource.system()))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(Substrate.class)).isSameAs(mine);
              });
    }
  }

  @Nested
  class TheFactStream {

    /**
     * Renamed to what it proves (Task 1 review, finding #6): with no {@code DataSource} there is no
     * projection to be "alongside", so this is the plain claim — a {@code HarnessObserver} bean is
     * subscribed to the fact stream and sees every fold. That it is subscribed ALONGSIDE the
     * starter's own is {@link WithADataSource#every_observer_bean_is_subscribed_together}'s job,
     * where a second subscriber actually exists to share the stream with.
     */
    @Test
    void a_user_supplied_observer_sees_every_fold() {
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

  /**
   * A DataSource that would fail on first use and never gets one: these tests assert which beans
   * the conditions chose, and no bean here opens a connection to be built.
   */
  private static DataSource unreachableDataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl("jdbc:postgresql://localhost:1/never-contacted");
    return dataSource;
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

  /** One tool bean, declared the way an application declares one: as a concrete {@code Tool<X>}. */
  @Configuration(proxyBeanMethods = false)
  static class NoteToolConfiguration {

    private final RecordingNoteTool note;

    NoteToolConfiguration(RecordingNoteTool note) {
      this.note = note;
    }

    @Bean
    Tool<Note> noteTool() {
      return Tool.of(
          Note.class, t -> t.name("note").description("writes a note").executes(note::write));
    }
  }

  /** Records what the model asked it to write, so the test can prove the tool actually ran. */
  static final class RecordingNoteTool {

    private final List<String> written = new CopyOnWriteArrayList<>();

    String write(Note note) {
      written.add(note.text());
      return "written";
    }

    List<String> written() {
      return List.copyOf(written);
    }
  }

  /** One tool-calling turn, then one that answers — the shape that exercises the registry. */
  private static Model callsTheNoteTool() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("text", "hello");
    return ScriptedModel.script(
        s -> s.toolUse("c1", "note", arguments).endWithToolUse().text("done").endTurn());
  }

  @Configuration(proxyBeanMethods = false)
  static class TellerConfiguration {

    @Bean
    Teller teller(Harness<String> harness) {
      return new Teller(harness);
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
