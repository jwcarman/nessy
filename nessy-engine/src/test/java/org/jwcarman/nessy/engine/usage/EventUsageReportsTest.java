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
package org.jwcarman.nessy.engine.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ModelUsage;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.UsageReport;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.inference.Failure;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EventUsageReportsTest {

  private static final AgentType TYPE = new AgentType("desk");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final InMemoryAgentEvents events = new InMemoryAgentEvents(codecs, Clock.systemUTC());
  private final InMemoryPayloads payloads = new InMemoryPayloads(codecs);
  private Seq last = Seq.NONE;

  private void append(InMemoryAgentEvents store, AgentEvent... appended) {
    store.append(TYPE, AGENT, List.of(appended), last);
    last = appended[appended.length - 1].seq();
  }

  private PayloadRef said(String text) {
    return payloads.put(List.of(new Block.Text(text)));
  }

  private static Usage usage(String model, int input, int output, Integer cacheRead) {
    return new Usage(
        model,
        Tokens.of(input),
        Tokens.of(output),
        Tokens.reported(cacheRead),
        Tokens.none(),
        Tokens.none());
  }

  /** One turn that spent tokens five ways, on two models, plus one inference that reported none. */
  private void aTurnThatSpentEveryWay(InMemoryAgentEvents store) {
    TurnId turn = new TurnId(1);
    append(
        store,
        new AgentEvent.TurnStarted(new Seq(1), turn, said("q"), Instant.now()),
        new AgentEvent.ActionsRequested(
            new Seq(2), turn, said("calls"), List.of(), usage("big", 1000, 50, 800)),
        new AgentEvent.InferenceAttempted(
            new Seq(3), turn, new Failure.Transient("timeout"), usage("big", 900, 0, null)),
        new AgentEvent.InferenceRefused(new Seq(4), turn, "policy", usage("big", 100, 5, null)),
        new AgentEvent.InferenceFailed(
            new Seq(5), turn, new Failure.Permanent("bad"), usage("small", 30, 2, null)),
        new AgentEvent.InferenceAnswered(new Seq(6), turn, said("a"), usage("small", 70, 8, null)),
        new AgentEvent.InferenceAnswered(new Seq(7), turn, said("b"), Usage.unreported()));
  }

  @Test
  void every_inference_counts_once_under_its_own_model_and_models_are_never_added() {
    aTurnThatSpentEveryWay(events);

    UsageReport report = new EventUsageReports(List.of(events)).of(TYPE, AGENT);

    assertThat(report.type()).isEqualTo(TYPE);
    assertThat(report.id()).isEqualTo(AGENT);
    assertThat(report.byModel())
        .containsExactly(
            new ModelUsage(
                "big",
                3,
                Tokens.of(2000),
                Tokens.of(55),
                Tokens.of(800),
                Tokens.none(),
                Tokens.none()),
            new ModelUsage(
                "small",
                2,
                Tokens.of(100),
                Tokens.of(10),
                Tokens.none(),
                Tokens.none(),
                Tokens.none()));
    assertThat(report.unreported()).isEqualTo(1);
  }

  @Test
  void an_agent_with_no_story_has_used_nothing() {
    UsageReport report = new EventUsageReports(List.of(events)).of(TYPE, AGENT);

    assertThat(report.byModel()).isEmpty();
    assertThat(report.unreported()).isZero();
  }

  @Test
  void the_story_is_read_from_the_one_store_that_holds_it_and_never_twice() {
    InMemoryAgentEvents other = new InMemoryAgentEvents(codecs, Clock.systemUTC());
    aTurnThatSpentEveryWay(other);

    UsageReport elsewhere = new EventUsageReports(List.of(events, other)).of(TYPE, AGENT);
    UsageReport sameTwice = new EventUsageReports(List.of(other, other)).of(TYPE, AGENT);

    assertThat(elsewhere.byModel()).extracting(ModelUsage::inferences).containsExactly(3, 2);
    assertThat(sameTwice.byModel()).extracting(ModelUsage::inferences).containsExactly(3, 2);
  }
}
