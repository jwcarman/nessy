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

package org.jwcarman.nessy.engine.direct;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.engine.core.AgentEvents;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.engine.store.JdbcAgentEvents;
import org.jwcarman.nessy.engine.store.JdbcPayloads;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Usage;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.spi.store.Payloads;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
            Usage.unknown());
  }

  /** A harness that shares nothing with another but the database -- which is what a restart is. */
  private DirectHarness<String, String> harness(InferenceProvider model) {
    return DefaultDirectHarnessFactory.of(
            f ->
                f.locks(new InMemoryLocks())
                    .events(events)
                    .payloads(payloads)
                    .provider(model)
                    .schemas(new VictoolsInputSchemaGenerator())
                    .mapper(JsonMapper.builder().build()))
        .<String>create(
            TYPE,
            c ->
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(in -> in.model("a-model")));
  }

  @Test
  @DisplayName("a turn survives the harness that ran it")
  void a_conversation_outlives_its_process() {
    AgentId agent = AgentId.random();

    Outcome<String> first =
        harness(saying("the capital is Paris")).ask(agent, "capital of France?");

    assertThat(first).isEqualTo(new Outcome.Answered<>("the capital is Paris"));

    // A different harness over the same database: no shared memory, no shared objects.
    InferenceProvider second = saying("and Japan's is Tokyo");
    Outcome<String> answered = harness(second).ask(agent, "and Japan?");

    assertThat(answered).isEqualTo(new Outcome.Answered<>("and Japan's is Tokyo"));
    assertThat(events.readFrom(agent, org.jwcarman.nessy.inference.Seq.NONE))
        .as("both turns, in one story")
        .hasSize(4);
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
          return new InferenceResult.Answer(List.of(new Block.Text("Tokyo")), Usage.unknown());
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
        .isEqualTo(new Outcome.Answered<>("again"));
  }

  @Test
  @DisplayName("a terminated agent stays terminated across processes")
  void termination_is_durable() {
    AgentId agent = AgentId.random();
    harness(saying("hello")).ask(agent, "hi");

    harness(saying("never")).terminate(agent);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> harness(saying("never")).ask(agent, "still there?"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accepts nothing further");
  }
}
