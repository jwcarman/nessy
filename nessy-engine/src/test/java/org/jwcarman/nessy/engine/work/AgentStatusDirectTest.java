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
package org.jwcarman.nessy.engine.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryChapters;
import org.jwcarman.nessy.backend.inmemory.InMemoryLeases;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/** What an agent is doing on the direct door, which keeps no queue and no effect rows. */
@DisplayName("An agent's status on the direct door")
class AgentStatusDirectTest {

  private static final AgentType TYPE = new AgentType("direct-status");
  private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

  private record Backend(
      Locks locks, InMemoryAgentEvents events, Payloads payloads, Chapters chapters, Leases leases)
      implements DirectBackend {}

  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final InMemoryAgentEvents events = new InMemoryAgentEvents(codecs);
  private final CountDownLatch inModel = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final AgentId agent = AgentId.random();

  private DefaultDirectHarnessFactory factory(InferenceProvider model) {
    Backend backend =
        new Backend(
            new InMemoryLocks(),
            events,
            new InMemoryPayloads(codecs),
            new InMemoryChapters(codecs),
            new InMemoryLeases());
    return DefaultDirectHarnessFactory.of(
        c ->
            c.backend(backend)
                .provider(ProviderId.of("test"), model)
                .schemas(new VictoolsJsonSchemaGenerator())
                .mapper(JsonMapper.builder().build())
                .clock(Clock.fixed(NOW, ZoneOffset.UTC)));
  }

  private DirectHarness<String, String> harness(DefaultDirectHarnessFactory factory) {
    return factory.<String>create(
        TYPE,
        c ->
            c.inputRenderer(text -> List.of(new Block.Text(text)))
                .inference(in -> in.provider("test").model("a-model")));
  }

  private static final InferenceProvider ANSWERING =
      (request, _) -> new InferenceResult.Answer(List.of(new Block.Text("ok")));

  @Test
  void an_agent_nobody_has_asked_is_idle_with_nothing_queued() {
    try (DefaultDirectHarnessFactory factory = factory(ANSWERING)) {
      AgentStatus status = factory.work().status(TYPE, agent);

      assertThat(status)
          .isEqualTo(new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), 0));
    }
  }

  @Test
  void an_agent_in_the_middle_of_an_ask_is_working_on_its_turn() throws InterruptedException {
    InferenceProvider held =
        (request, _) -> {
          inModel.countDown();
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new InferenceResult.Answer(List.of(new Block.Text("ok")));
        };
    try (DefaultDirectHarnessFactory factory = factory(held)) {
      DirectHarness<String, String> harness = harness(factory);
      Thread asking = Thread.ofVirtual().start(() -> harness.ask(agent, "go"));
      assertThat(inModel.await(20, TimeUnit.SECONDS)).isTrue();

      AgentStatus status = factory.work().status(TYPE, agent);
      release.countDown();
      asking.join();

      assertThat(status)
          .isEqualTo(
              new AgentStatus(Activity.WORKING, 0, Optional.of(new TurnId(1)), List.of(), 0));
    }
  }

  @Test
  void an_agent_whose_ask_has_returned_is_idle() {
    try (DefaultDirectHarnessFactory factory = factory(ANSWERING)) {
      harness(factory).ask(agent, "go");

      AgentStatus status = factory.work().status(TYPE, agent);

      assertThat(status)
          .isEqualTo(new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), 0));
    }
  }

  @Test
  void a_terminated_agent_has_ended() {
    try (DefaultDirectHarnessFactory factory = factory(ANSWERING)) {
      harness(factory).ask(agent, "go");
      Seq last = events.readAll(TYPE, agent).getLast().seq();
      events.append(TYPE, agent, List.of(new AgentEvent.Terminated(last.next())), last, NOW);

      AgentWork work = factory.work();

      assertThat(work.status(TYPE, agent))
          .isEqualTo(new AgentStatus(Activity.ENDED, 0, Optional.empty(), List.of(), 0));
    }
  }
}
