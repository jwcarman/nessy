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
package org.jwcarman.nessy.spi.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.event.EnrichmentFailed;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.SessionState;

/**
 * The pipeline executor itself, in isolation from {@code InProcessEngine} — declaration order for
 * both bindings, per-contributor enrichment attribution, and {@link ContextPipeline.Placement}.
 * Engine-level enrichment scenarios (enrichment, failure, pairing) live in {@code
 * InProcessEngineEnrichmentTest}, which exercises the same pipeline through {@code requestFor}.
 */
class ContextPipelineTest {

  private static SessionState stateWith(Message... messages) {
    return SessionState.newSession(SessionId.generate()).withMessages(List.of(messages));
  }

  private static List<Message> concat(List<Message> head, List<Message> tail) {
    List<Message> combined = new ArrayList<>(head);
    combined.addAll(tail);
    return combined;
  }

  @Test
  void projections_apply_in_declaration_order() {
    Projection appendFirst =
        context -> Context.of(concat(context.messages(), List.of(Message.user("first"))));
    Projection appendSecond =
        context -> Context.of(concat(context.messages(), List.of(Message.user("second"))));
    ContextPipeline pipeline =
        ContextPipeline.builder()
            .project(appendFirst)
            .project(appendSecond)
            .build(EventHub.synchronous(), ObservationRegistry.NOOP);

    Context assembled = pipeline.assemble(stateWith(Message.user("start")));

    assertThat(assembled.messages())
        .containsExactly(Message.user("start"), Message.user("first"), Message.user("second"));
  }

  @Test
  void enrichment_contributions_concatenate_in_declaration_order() {
    ContextEnricher first = state -> List.of(Message.user("fact A"));
    ContextEnricher second = state -> List.of(Message.user("fact B"));
    ContextPipeline pipeline =
        ContextPipeline.builder()
            .enrich(first)
            .enrich(second)
            .build(EventHub.synchronous(), ObservationRegistry.NOOP);

    Context assembled = pipeline.assemble(stateWith(Message.user("hi")));

    assertThat(assembled.messages())
        .containsExactly(Message.user("fact A"), Message.user("fact B"), Message.user("hi"));
  }

  @Test
  void one_failing_contributor_costs_only_its_contribution() {
    ContextEnricher failing =
        state -> {
          throw new IllegalStateException("A exploded");
        };
    ContextEnricher succeeding = state -> List.of(Message.user("fact B"));
    EventHub hub = EventHub.synchronous();
    List<EnrichmentFailed> failures = new ArrayList<>();
    hub.subscribe(EnrichmentFailed.class, failures::add);
    ContextPipeline pipeline =
        ContextPipeline.builder()
            .enrich(failing)
            .enrich(succeeding)
            .build(hub, ObservationRegistry.NOOP);

    Context assembled = pipeline.assemble(stateWith(Message.user("hi")));

    assertThat(failures).hasSize(1);
    assertThat(failures.getFirst().reason()).contains("A exploded");
    assertThat(assembled.messages()).containsExactly(Message.user("fact B"), Message.user("hi"));
  }

  @Test
  void placement_is_policy() {
    ContextEnricher enricher = state -> List.of(Message.user("a fact"));
    ContextPipeline pipeline =
        ContextPipeline.builder()
            .enrich(enricher)
            .placement(ContextPipeline.Placement.ENRICHMENTS_LAST)
            .build(EventHub.synchronous(), ObservationRegistry.NOOP);

    Context assembled = pipeline.assemble(stateWith(Message.user("hi")));

    assertThat(assembled.messages()).containsExactly(Message.user("hi"), Message.user("a fact"));
  }
}
