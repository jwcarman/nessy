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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.event.EnrichmentFailed;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.event.ListenerRegistration;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * The pipeline executor itself, in isolation from {@code InProcessEngine} — declaration order for
 * both bindings, per-contributor enrichment attribution, and {@link ContextPipeline.Placement}.
 * Engine-level enrichment scenarios (enrichment, failure, pairing) live in {@code
 * InProcessEngineEnrichmentTest}, which exercises the same pipeline through {@code requestFor}.
 */
class ContextPipelineTest {

  private static ConversationState stateWith(Message... messages) {
    return ConversationState.newConversation(ConversationId.generate())
        .withMessages(List.of(messages));
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
            .build(EventEmitter.noop(), ObservationRegistry.NOOP);

    Context assembled = pipeline.assemble(stateWith(Message.user("start")));

    assertThat(assembled.messages())
        .containsExactly(Message.user("start"), Message.user("first"), Message.user("second"));
  }

  @Test
  void the_lambda_idiom_pins_elideToolResults_end_to_end() {
    // Proves the factory's death didn't lose behavior: the standard idiom for eliding tool
    // results is now a plain lambda over Context's own edit algebra, run through the pipeline
    // exactly like any other projection.
    Message toolUse =
        Message.assistant(
            List.of(
                new ToolUseBlock(
                    new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode()))));
    Message toolResult =
        Message.toolResults(List.of(new ToolResultBlock("c1", "forty-two", false)));
    ContextPipeline pipeline =
        ContextPipeline.builder()
            .project(ctx -> ctx.elideToolResults(0))
            .build(EventEmitter.noop(), ObservationRegistry.NOOP);

    Context assembled = pipeline.assemble(stateWith(toolUse, toolResult));

    ToolResultBlock elided = (ToolResultBlock) assembled.messages().get(1).content().getFirst();
    assertThat(elided.content()).isEqualTo("[elided]");
    assertThat(elided.toolUseId()).isEqualTo("c1");
  }

  @Test
  void enrichment_contributions_concatenate_in_declaration_order() {
    ContextEnricher first = state -> List.of(Message.user("fact A"));
    ContextEnricher second = state -> List.of(Message.user("fact B"));
    ContextPipeline pipeline =
        ContextPipeline.builder()
            .enrich(first)
            .enrich(second)
            .build(EventEmitter.noop(), ObservationRegistry.NOOP);

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
    List<EnrichmentFailed> failures = new ArrayList<>();
    ListenerRegistry hub =
        ListenerRegistry.of(
            List.of(ListenerRegistration.sync(EnrichmentFailed.class, failures::add)));
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
            .build(EventEmitter.noop(), ObservationRegistry.NOOP);

    Context assembled = pipeline.assemble(stateWith(Message.user("hi")));

    assertThat(assembled.messages()).containsExactly(Message.user("hi"), Message.user("a fact"));
  }
}
