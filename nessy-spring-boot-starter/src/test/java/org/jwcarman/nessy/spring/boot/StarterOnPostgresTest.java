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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.jdbc.JdbcContinuumRepository;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.ApprovalDesk;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate;
import org.jwcarman.nessy.testing.ScriptedModel;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole starter, end to end, on a real PostgreSQL: Boot discovers the auto-configuration for
 * itself, a scripted model proposes a call the grant defers, the {@code tell} parks it, the
 * projection shows it with the frozen request, the desk answers it, and the row changes.
 *
 * <p>The second half is the one that could only be written once both stores were durable: a SECOND
 * application context over the SAME database sees the same parked approval. That is the rule
 * harness.md states — two harnesses of one type share both stores or neither — proved rather than
 * asserted.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(
    classes = {StarterOnPostgresTest.TestApplication.class, StarterOnPostgresTest.Recipe.class},
    properties = {"nessy.type=ops", "nessy.system-prompt=you restart things"})
class StarterOnPostgresTest {

  // glibc image, never -alpine: see JdbcSubstrateContractTest for why.
  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

  private static final String SCOPE = "prod-eu";

  @Autowired private Harness<String> harness;
  @Autowired private ApprovalDesk approvals;
  @Autowired private PendingApprovalsRepository pending;
  @Autowired private DataSource dataSource;
  @Autowired private ConfigurableApplicationContext context;

  record Restart(String target) {}

  @Test
  void a_parked_approval_is_listed_answered_and_visible_to_a_second_context() {
    harness.bind(AgentId.of(SCOPE)).tell("please restart " + SCOPE);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pending.pending()).hasSize(1));

    PendingApproval parked = pending.pending().getFirst();
    assertThat(parked.agentId()).contains(SCOPE);
    assertThat(parked.action()).contains("restart " + SCOPE);
    assertThat(parked.requestJson()).isPresent();
    assertThat(parked.answer()).isEmpty();

    // A second, independent context over the same database — different harness object, same rows.
    // It must see the same waiting approval, because the projection is a table and the stores are
    // shared. Asserted BEFORE the answer, so it cannot pass by watching an already-settled row.
    secondContext()
        .run(
            other -> {
              List<PendingApproval> theirs =
                  other.getBean(PendingApprovalsRepository.class).pending();
              assertThat(theirs).isNotEmpty();
              assertThat(theirs)
                  .singleElement()
                  .satisfies(
                      row -> assertThat(row.computationId()).isEqualTo(parked.computationId()));
            });

    approvals.approve(AgentId.of(SCOPE), "c1", "ops-desk", "go ahead");

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(pending.pending()).isEmpty();
              List<PendingApproval> recent = pending.recent(50);
              assertThat(recent).isNotEmpty();
              assertThat(recent)
                  .singleElement()
                  .satisfies(
                      row -> {
                        assertThat(row.answer()).contains("approved");
                        assertThat(row.answeredAt()).isPresent();
                      });
            });
  }

  /**
   * The spec's shutdown row: Spring calls {@code harness.shutdown()} when the context closes. The
   * harness is not {@code AutoCloseable}, so this is a named destroy method — which is exactly what
   * is asserted, since a closed scheduler has no public tell.
   */
  @Test
  void the_harness_bean_shuts_down_with_the_context() {
    assertThat(context.getBeanFactory().getBeanDefinition("nessyHarness").getDestroyMethodName())
        .isEqualTo("shutdown");
  }

  private ApplicationContextRunner secondContext() {
    DataSource shared = dataSource;
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
        .withBean(DataSource.class, () -> shared)
        .withUserConfiguration(Recipe.class)
        .withPropertyValues("nessy.type=ops", "nessy.system-prompt=you restart things");
  }

  /**
   * {@code @EnableAutoConfiguration} rather than {@code @SpringBootApplication}: no component scan,
   * so the only auto-configuration in play is the one Boot discovers through the starter's own
   * {@code AutoConfiguration.imports} file — which is half of what this test exists to prove.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class TestApplication {

    /**
     * The DataSource, with all three shipped schemas applied before anything else can touch it —
     * the substrate's, the Continuum's, and the starter's own. Applying them here rather than in a
     * {@code @BeforeAll} is deliberate: the context is built lazily on first use, and the harness's
     * pumps start querying the moment it is.
     */
    @Bean
    DataSource dataSource() {
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setUrl(POSTGRES.getJdbcUrl());
      dataSource.setUser(POSTGRES.getUsername());
      dataSource.setPassword(POSTGRES.getPassword());
      applySchemas(dataSource);
      return dataSource;
    }
  }

  /** The application's half of the bargain: a model, and a tool whose grant defers. */
  @Configuration(proxyBeanMethods = false)
  static class Recipe {

    @Bean
    Model model() {
      return ScriptedModel.script(
          s ->
              s.toolUse(
                      "c1",
                      "restart_prod",
                      JsonNodeFactory.instance.objectNode().put("target", SCOPE))
                  .endWithToolUse()
                  .text("Restarted " + SCOPE + ".")
                  .endTurn());
    }

    @Bean
    ToolGrant restartProd() {
      Tool<Restart> tool =
          Tool.of(
              Restart.class,
              t ->
                  t.name("restart_prod")
                      .description("restarts a production target; requires human approval")
                      .requires(CompletionPolicy.DURABLE)
                      .executes(input -> "restarted " + input.target()));
      return ToolGrant.grant(tool, input -> "restart " + input.target(), Approvers.defer());
    }
  }

  private static void applySchemas(DataSource dataSource) {
    String substrate = resource(JdbcSubstrate.class, "nessy-postgresql.sql");
    String continuum = resource(JdbcContinuumRepository.class, "continuum-postgresql.sql");
    String projection = resource(PendingApprovals.class, "pending-approvals-postgresql.sql");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(substrate);
      statement.execute(continuum);
      statement.execute(projection);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to apply the shipped schemas", e);
    }
  }

  private static String resource(Class<?> beside, String name) {
    try (InputStream in = beside.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException(name + " not found beside " + beside.getName());
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
