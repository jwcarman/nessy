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

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentTurns;
import org.jwcarman.nessy.backend.inmemory.InMemoryChapters;
import org.jwcarman.nessy.backend.inmemory.InMemoryLeases;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/** A turn that recovery closes on a later caller's behalf still leaves its row. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessRecoveryTrajectoryTest {

  private static final AgentType TYPE = new AgentType("chat");

  private final InMemoryAgentTurns turns = new InMemoryAgentTurns();
  private final AgentEvents events =
      new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));

  private DirectHarness<String, String> harness() {
    JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
    InferenceProvider answers =
        (request, narrator) ->
            new InferenceResult.Answer(
                List.of(new Block.Text("an answer")), Usage.unreported("a-model"));
    return DefaultDirectHarnessFactory.of(
            f ->
                f.backend(
                        new FixedDirectBackend(
                            new InMemoryLocks(),
                            events,
                            new InMemoryPayloads(codecs),
                            new InMemoryChapters(codecs),
                            new InMemoryLeases(),
                            turns))
                    .provider(ProviderId.of("test"), answers)
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build())
                    .observations(ObservationRegistry.NOOP))
        .<String>create(
            TYPE,
            c ->
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(in -> in.provider("test").model("a-model")));
  }

  @Test
  void a_turn_recovery_closes_on_the_next_callers_behalf_still_leaves_its_row() {
    AgentId agent = new AgentId(UUID.randomUUID());
    Instant aDayAgo = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
    events.append(
        TYPE,
        agent,
        List.of(
            new AgentEvent.TurnStarted(
                new Seq(1),
                new TurnId(1),
                PayloadRef.of("abandoned"),
                "Question",
                aDayAgo,
                aDayAgo)),
        Seq.NONE,
        aDayAgo);
    DirectHarness<String, String> harness = harness();
    harness.ask(agent, "look up 7");
    List<AgentTurn> rows = turns.of(TYPE, agent);
    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().turn()).isEqualTo(new TurnId(1));
    assertThat(rows.getFirst().outcome()).isEqualTo(TurnOutcome.FAILED);
    assertThat(rows.getFirst().endingSeq()).isEqualTo(new Seq(2));
    assertThat(rows.getFirst().rounds()).isZero();
    assertThat(rows.getLast().turn()).isEqualTo(new TurnId(3));
  }
}
