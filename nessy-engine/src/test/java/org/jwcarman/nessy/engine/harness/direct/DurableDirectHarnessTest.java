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

package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AskOutcome;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.jdbc.JdbcAgentEvents;
import org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend;
import org.jwcarman.nessy.backend.jdbc.JdbcPayloads;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The direct door over a database, which is the pair of stores working for real.
 *
 * <p>What it proves is the thing neither store can prove alone: a conversation written by one
 * harness is read back by another that shares nothing but the database -- which is what a second
 * process is, and the whole reason the events and the content are durable at all.
 */
@Tag("container")
@DisplayName("A direct harness over a database")
class DurableDirectHarnessTest {

  /** Stands in for a tally nobody is asserting on, and is never compared. */
  private static final TurnStats ANY_STATS = TurnStats.opened(Instant.EPOCH);

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType TYPE = new AgentType("chat");

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final DataSource database = database();
  private final JdbcClient jdbc = JdbcClient.create(database);
  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final AgentEvents events = new JdbcAgentEvents(jdbc, codecs);
  private final Payloads payloads = new JdbcPayloads(jdbc, codecs);

  /** Answers with whatever it is handed, in order. */
  private static InferenceProvider saying(String... answers) {
    List<String> said = List.of(answers);
    int[] next = {0};
    return (request, narrator) ->
        new InferenceResult.Answer(
            List.of(new Block.Text(said.get(Math.min(next[0]++, said.size() - 1)))),
            Usage.unreported());
  }

  /** A harness that shares nothing with another but the database -- which is what a restart is. */
  private DirectHarness<String, String> harness(InferenceProvider model) {
    return DefaultDirectHarnessFactory.of(
            f ->
                f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                    .provider(ProviderId.of("test"), model)
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build()))
        .<String>create(
            TYPE,
            c ->
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(in -> in.provider("test").model("a-model")));
  }

  @Test
  @DisplayName("a turn survives the harness that ran it")
  void a_conversation_outlives_its_process() {
    AgentId agent = AgentId.random();

    AskOutcome<String> first =
        harness(saying("the capital is Paris")).ask(agent, "capital of France?");

    assertThat(first)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new AskOutcome.Answered<>("the capital is Paris", ANY_STATS));

    // A different harness over the same database: no shared memory, no shared objects.
    InferenceProvider second = saying("and Japan's is Tokyo");
    AskOutcome<String> answered = harness(second).ask(agent, "and Japan?");

    assertThat(answered)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new AskOutcome.Answered<>("and Japan's is Tokyo", ANY_STATS));
    assertThat(events.readAll(TYPE, agent)).as("both turns, in one story").hasSize(4);
  }

  @Test
  @DisplayName("the second turn is shown the first, read back out of the database")
  void the_model_is_shown_what_it_said_before() {
    AgentId agent = AgentId.random();
    harness(saying("Paris")).ask(agent, "capital of France?");

    List<InferenceRequest> seen = new java.util.ArrayList<>();
    InferenceProvider watching =
        (request, narrator) -> {
          seen.add(request);
          return new InferenceResult.Answer(List.of(new Block.Text("Tokyo")), Usage.unreported());
        };

    harness(watching).ask(agent, "and Japan?");

    assertThat(seen).hasSize(1);
    assertThat(seen.getFirst().context().turns())
        .as("the turn it cannot remember, because it was a different object")
        .hasSize(2);
    assertThat(seen.getFirst().context().turns().getFirst().result())
        .as("with what was actually said, resolved from the payload table")
        .isNotNull();
  }

  @Test
  @DisplayName("content is scoped to its agent, so one agent's story resolves only its own")
  void agents_do_not_share_content() {
    AgentId mine = AgentId.random();
    AgentId theirs = AgentId.random();

    harness(saying("mine")).ask(mine, "who am I?");
    harness(saying("theirs")).ask(theirs, "who am I?");

    assertThat(harness(saying("again")).ask(mine, "again?"))
        .as("reading one agent back does not need another agent's content")
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new AskOutcome.Answered<>("again", ANY_STATS));
  }

  @Test
  @DisplayName("a terminated agent stays terminated across processes")
  void termination_is_durable() {
    AgentId agent = AgentId.random();
    harness(saying("hello")).ask(agent, "hi");

    harness(saying("never")).terminate(agent);

    assertThat(harness(saying("never")).ask(agent, "still there?"))
        .as("a caller who is owed an answer gets one, even when the answer is no")
        .isEqualTo(new AskOutcome.Terminated<String>());
  }

  /**
   * The case nessy-ap met (F14): a Spring application calls the direct door from inside its own
   * transaction, on the same transaction manager the store uses. The turn's first step would join
   * that transaction, and the model call, on another connection, could not see it, failing with "no
   * event at 1". It is refused, plainly, before anything is written.
   */
  @Test
  void a_turn_asked_inside_a_callers_transaction_is_refused_and_writes_nothing() {
    DataSourceTransactionManager transactions = new DataSourceTransactionManager(database);
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new JdbcDirectBackend(database, transactions, codecs))
                        .provider(ProviderId.of("test"), saying("the capital is Paris"))
                        .schemas(new VictoolsJsonSchemaGenerator())
                        .mapper(JsonMapper.builder().build()))
            .<String>create(
                TYPE,
                c ->
                    c.systemPrompt("You are terse.")
                        .inputRenderer(said -> List.of(new Block.Text(said)))
                        .inference(in -> in.provider("test").model("a-model")));
    AgentId agent = AgentId.random();

    Throwable refused =
        new TransactionTemplate(transactions)
            .execute(status -> catchThrowable(() -> harness.ask(agent, "capital of France?")));

    assertThat(refused)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot run inside a caller's transaction");
    assertThat(events.readAll(TYPE, agent)).as("nothing was written").isEmpty();
    assertThat(harness.ask(agent, "capital of France?"))
        .as("outside the transaction the same call answers")
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new AskOutcome.Answered<>("the capital is Paris", ANY_STATS));
  }

  private DirectHarness<String, String> overJdbc(AgentType type, DirectBackend backend) {
    return DefaultDirectHarnessFactory.of(
            f ->
                f.backend(backend)
                    .provider(ProviderId.of("test"), saying("Paris"))
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build()))
        .<String>create(
            type,
            c ->
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(in -> in.provider("test").model("a-model")));
  }

  @Test
  @DisplayName("a turn over the JDBC direct backend leaves its row in nessy_agent_turn")
  void a_turn_over_the_jdbc_direct_backend_leaves_its_row() {
    JdbcDirectBackend backend =
        new JdbcDirectBackend(database, new DataSourceTransactionManager(database), codecs);
    AgentId agent = AgentId.random();

    overJdbc(TYPE, backend).ask(agent, "capital of France?");

    List<AgentTurn> rows = backend.turns().of(TYPE, agent);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().outcome()).isEqualTo(TurnOutcome.ANSWERED);
    assertThat(rows.getFirst().endingSeq()).isEqualTo(events.readAll(TYPE, agent).getLast().seq());
  }

  @Test
  @DisplayName("a row the JDBC direct backend cannot write takes the ending event with it")
  void a_row_that_cannot_be_written_rolls_the_ending_back_on_the_direct_door() {
    JdbcDirectBackend real =
        new JdbcDirectBackend(database, new DataSourceTransactionManager(database), codecs);
    AgentId agent = AgentId.random();
    // A type of its own: the other tests in this class commit sightings under "chat".
    AgentType own = new AgentType("t" + UUID.randomUUID().toString().substring(0, 8));

    // Whether the ask throws or reports a failure is the door's business; what was committed is
    // the question.
    DirectHarness<String, String> harness = overJdbc(own, new RefusingRows(real));
    catchThrowable(() -> harness.ask(agent, "capital of France?"));

    List<AgentEvent> story = events.readAll(own, agent);
    assertThat(story).isNotEmpty().noneMatch(AgentEvent.InferenceAnswered.class::isInstance);
    assertThat(real.turns().of(own, agent)).isEmpty();
    assertThat(
            JdbcClient.create(database)
                .sql("SELECT COUNT(*) FROM nessy_known_trajectory WHERE agent_type = ?")
                .params(own.value())
                .query(Long.class)
                .single())
        .isZero();
  }

  /** The JDBC direct backend, except that a turn's row is never written. */
  private record RefusingRows(DirectBackend backend) implements DirectBackend {

    @Override
    public AgentEvents events() {
      return backend.events();
    }

    @Override
    public Payloads payloads() {
      return backend.payloads();
    }

    @Override
    public Locks locks() {
      return backend.locks();
    }

    @Override
    public Chapters chapters() {
      return backend.chapters();
    }

    @Override
    public Leases leases() {
      return backend.leases();
    }

    @Override
    public AgentTurns turns() {
      AgentTurns real = backend.turns();
      return new AgentTurns() {
        @Override
        public boolean firstSighting(
            AgentType type, String label, Trajectory trajectory, Instant at) {
          return real.firstSighting(type, label, trajectory, at);
        }

        @Override
        public void append(AgentType type, AgentId agent, AgentTurn turn) {
          throw new IllegalStateException("refused");
        }

        @Override
        public List<AgentTurn> of(AgentType type, AgentId agent) {
          return real.of(type, agent);
        }
      };
    }
  }
}
