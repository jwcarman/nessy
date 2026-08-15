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
package org.jwcarman.nessy.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.WrongAgentException;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end proof, against a real Postgres, that the agent-name stamp (design §3) survives the
 * JDBC registry: a park written by one named agent cannot be resumed by another, and the token
 * outlives the {@link Harness}/{@link Agent} instances that minted it — a fresh pair built over the
 * same database, the "restart" a durable park is meant to survive (design §5, §8). Requires Docker;
 * tagged {@code container} so the offline default build never needs it.
 *
 * <p>The plain roundtrip (a park written by a named agent comes back from {@code find}/{@code
 * forConversation} carrying that name) is already pinned by {@link
 * org.jwcarman.nessy.tck.ParksContract} run against {@link JdbcParks} in {@link JdbcParksTest}:
 * {@code Park} is a record, so {@code assertThat(parks().find(token)).contains(park)} cannot pass
 * unless {@code agent_name} round-trips exactly — a null or mangled column fails that assertion
 * today. Nothing here duplicates that ground; this class covers the one thing the contract cannot
 * express on its own: a second, differently-named agent going through the same JDBC-backed registry
 * and being refused.
 */
@Testcontainers
@Tag("container")
class JdbcCallbackDoorsTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  private static DataSource dataSource;

  @BeforeAll
  static void nessy_jdbc_test_points_a_data_source_at_the_container() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void an_empty_conversation_and_parks_table() {
    JdbcConversationStore.create(dataSource, new ObjectMapper());
    JdbcParks.create(dataSource, new ObjectMapper());
    truncateEveryTable();
  }

  private void truncateEveryTable() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE nessy_conversation, nessy_inbox, nessy_parks");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to truncate tables between tests", e);
    }
  }

  /** A provider that replays one scripted turn (a list of {@link ModelEvent}) per call. */
  private static final class ScriptedProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();

    ScriptedProvider turn(ModelEvent... events) {
      turns.addLast(List.of(events));
      return this;
    }

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
  }

  record SearchInput(String query) {}

  /** A tool that always succeeds once invoked — the gate is what parks, not the tool itself. */
  private static final class SearchTool implements Tool<SearchInput> {

    @Override
    public String name() {
      return "search";
    }

    @Override
    public String description() {
      return "Searches for something";
    }

    @Override
    public Class<SearchInput> inputType() {
      return SearchInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(SearchInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("found:" + input.query()));
    }
  }

  /** Parks the first call it is asked, remembering the token it handed out. */
  private static final class ParkingApprover implements Approver {

    private ParkToken token;

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      token = ParkToken.generate();
      return Awaited.parked(token);
    }

    ParkToken token() {
      return token;
    }
  }

  /**
   * Design §5, §8: a token minted by one named agent, resumed through a JDBC-backed {@code
   * Harness}/{@code Agent} pair built fresh over the same database — the "restart" a durable park
   * exists to survive — is refused by a differently-named agent, and agent-a's conversation reads
   * back exactly as it stood before the refused call.
   */
  @Test
  void a_park_minted_by_one_named_agent_survives_a_restart_and_refuses_another_agents_resume() {
    ObjectMapper mapper = new ObjectMapper();
    ToolCall call = new ToolCall("a1", "search", JsonNodeFactory.instance.objectNode());
    ScriptedProvider provider =
        new ScriptedProvider()
            .turn(
                new ModelEvent.ToolUseEmitted(call),
                new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
    ParkingApprover approver = new ParkingApprover();
    ConversationStore store = JdbcConversationStore.create(dataSource, mapper);
    Parks parks = JdbcParks.create(dataSource, mapper);
    Harness harness = Nessy.harness(provider).store(store).parks(parks).build();
    Agent<String> agentA =
        harness
            .agent()
            .name("agent-a")
            .model("model-a")
            .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
            .approver(approver)
            .build();

    RunOutcome parked = agentA.converse().tell("search for a");
    ConversationId conversationId = parked.state().id();
    ParkToken token = approver.token();
    ConversationStore.Loaded before = store.load(conversationId).orElseThrow();

    // The "restart": a fresh Harness and Agent, built over the same underlying database from new
    // store/parks instances, exactly as a redeployed process would.
    ConversationStore restartedStore = JdbcConversationStore.create(dataSource, mapper);
    Parks restartedParks = JdbcParks.create(dataSource, mapper);
    Harness restartedHarness =
        Nessy.harness(new ScriptedProvider()).store(restartedStore).parks(restartedParks).build();
    Agent<String> agentB = restartedHarness.agent().name("agent-b").model("model-b").build();
    ToolResolution.Decided decided = new ToolResolution.Decided(Decision.allow());

    assertThatThrownBy(() -> agentB.resume(token, decided))
        .isInstanceOf(WrongAgentException.class)
        .hasMessage(
            "park "
                + token.value()
                + " was minted by agent 'agent-a'; this agent is 'agent-b' — an agent's name is a"
                + " durable wire contract; redeploy under 'agent-a' to drain its parks");
    assertThat(restartedStore.load(conversationId)).contains(before);
  }

  /**
   * The thinnest possible {@link DataSource}: a fresh {@link DriverManager} connection per call, no
   * pooling. Sufficient for a test that wants one connection per JDBC operation and nothing
   * fancier; a real deployment supplies its own pooled {@code DataSource} instead.
   */
  private static final class DriverManagerDataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;

    private DriverManagerDataSource(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pass) throws SQLException {
      return DriverManager.getConnection(url, username, pass);
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException("not used by JdbcCallbackDoorsTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by JdbcCallbackDoorsTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by JdbcCallbackDoorsTest");
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException("no java.util.logging parent logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
      throw new UnsupportedOperationException("not used by JdbcCallbackDoorsTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
