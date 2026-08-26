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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.InstantSource;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.jdbc.JdbcContinuumRepository;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentPhase;
import org.jwcarman.nessy.agent.store.SubstrateAgentPhaseStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Both halves of durability, composed for the first time: a {@link JdbcSubstrate} for the scope's
 * own state and a {@code continuum-jdbc}-backed {@link Continuum} for the approval and tool kinds,
 * over ONE PostgreSQL database whose schema is the two shipped DDL files executed verbatim.
 *
 * <p>Harness A asks, the policy says approval, the call parks — and A is shut down and dropped,
 * never having decided. Harness B is built from nothing but the same {@code DataSource}: fresh
 * substrate object, fresh repository, fresh Continuum, fresh pumps. B approves the id A's notifier
 * handed out, B's pumps claim the delivery from the database, the gate finds the decision, the tool
 * finally runs, and the turn A started completes on B. Nothing but the database carried the call
 * across. This is the payoff the continuum-adoption, JDBC-substrate and {@link
 * HarnessConfig#continuum} work was building toward, and the first test in the tree to exercise a
 * durable computation store at all.
 */
@Tag("container")
@Testcontainers
class DurableResumeTest {

  // glibc image, never -alpine: see JdbcSubstrateContractTest for why.
  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

  record RestartInput(String target) {}

  static final class RestartTool implements Tool<RestartInput> {

    @Override
    public String name() {
      return "restart_prod";
    }

    @Override
    public String description() {
      return "restarts a production target; requires human approval";
    }

    @Override
    public Class<RestartInput> inputType() {
      return RestartInput.class;
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }

    @Override
    public Awaited<ToolResult> execute(RestartInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("restarted " + input.target()));
    }
  }

  private static final ActionContributor<RestartInput, String> RESTART_ACTION =
      input -> "restart " + input.target();

  private static final String SCOPE = "prod-eu";

  @Test
  void aCallParkedByOneProcessIsDeliveredByTheNextOverTheSameDatabase() throws Exception {
    DataSource dataSource = dataSource();
    applyShippedSchemas(dataSource);
    var state =
        new SubstrateAgentPhaseStore(
            new JdbcSubstrate(dataSource), SCOPE, Clock.systemUTC(), TestMappers.plainlyPinned());
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", SCOPE));

    // "Process" A: its own substrate and Continuum objects over the database.
    var pumpA = new PumpedExecutor();
    var modelA = new ScriptedModel(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));
    var harnessA =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(modelA)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, Approvers.defer()))
                    .substrate(new JdbcSubstrate(dataSource))
                    .continuum(durableContinuum(dataSource))
                    .executor(pumpA));
    harnessA.bind(AgentId.of(SCOPE)).tell("please restart " + SCOPE);
    pumpA.pumpUntilQuiet();
    assertThat(state.load().value()).isInstanceOf(AgentPhase.AwaitingTools.class);
    assertThat(harnessA.approvals().request(AgentId.of(SCOPE), "c1").action()).contains("restart");
    harnessA.shutdown();

    // "Process" B: nothing survives from A but the rows. Its model only ever sees the resumed turn.
    var pumpB = new PumpedExecutor();
    var modelB =
        new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("Done — prod-eu restarted."))));
    var harnessB =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(modelB)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, Approvers.defer()))
                    .substrate(new JdbcSubstrate(dataSource))
                    .continuum(durableContinuum(dataSource))
                    .executor(pumpB));
    try {
      // The phase is the map (approval-lifecycle spec §1.6): process B answers by coordinates,
      // resolving the parked computation through the scope's own stored phase.
      harnessB.approvals().approve(AgentId.of(SCOPE), "c1", "ops-desk", "");

      long deadline = System.currentTimeMillis() + 10_000;
      while (!(state.load().value() instanceof AgentPhase.Idle)
          && System.currentTimeMillis() < deadline) {
        pumpB.pumpUntilQuiet();
        Thread.sleep(20);
      }

      assertThat(state.load().value()).isEqualTo(new AgentPhase.Idle());
      assertThat(modelB.requests()).hasSize(1);
    } finally {
      harnessB.shutdown();
    }
  }

  private static Continuum durableContinuum(DataSource dataSource) {
    return new DefaultContinuum(new JdbcContinuumRepository(dataSource), InstantSource.system());
  }

  private static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  /** Both shipped DDL files, verbatim — the files an application copies into its migrations. */
  private static void applyShippedSchemas(DataSource dataSource) {
    String nessy = resource(JdbcSubstrate.class, "nessy-postgresql.sql");
    String continuum = resource(JdbcContinuumRepository.class, "continuum-postgresql.sql");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(nessy);
      statement.execute(continuum);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to apply shipped schemas", e);
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
