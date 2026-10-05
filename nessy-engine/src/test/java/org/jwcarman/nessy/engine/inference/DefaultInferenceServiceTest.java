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
package org.jwcarman.nessy.engine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.backend.event.InferenceRequestManifest;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.ToolOffer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place a model request is assembled also says what the request was made of, and what it
 * says is checked by resolving every reference and comparing with the request the provider got.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DefaultInferenceServiceTest {

  private static final AgentType TYPE = new AgentType("desk");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final JsonSchema LOOKUP_SCHEMA =
      new JsonSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
  private static final JsonSchema ANSWER_SCHEMA =
      new JsonSchema("{\"type\":\"object\",\"properties\":{\"verdict\":{\"type\":\"string\"}}}");
  private static final List<ToolOffer> TOOLS =
      List.of(
          new ToolOffer(new ToolName("lookup"), "looks a thing up", LOOKUP_SCHEMA),
          new ToolOffer(
              new ToolName("another"),
              "does another thing",
              new JsonSchema("{\"type\":\"object\"}")));
  private static final InferenceOptions OPTIONS =
      new InferenceOptions("a-model", 4096, properties());

  private final Payloads payloads = new InMemoryPayloads(new JacksonCodecFactory(MAPPER));
  private final List<InferenceRequest> received = new ArrayList<>();
  private final InferenceProvider recording =
      (request, narrator) -> {
        received.add(request);
        return new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported());
      };

  /** Not alphabetical: the stored document must put them in order whatever order they came in. */
  private static Map<String, String> properties() {
    Map<String, String> ordered = new LinkedHashMap<>();
    ordered.put("vendor.zeta", "1");
    ordered.put("vendor.alpha", "2");
    return ordered;
  }

  private static Turn turn(long id) {
    return new Turn(
        new TurnId(id),
        new Input(new Seq(id), List.of(new Block.Text("q" + id))),
        List.of(),
        new TurnResult.Answered(List.of(new Block.Text("a" + id))));
  }

  private static Chapter chapter(long from, long through) {
    return new Chapter(TYPE, AGENT, new TurnId(from), new TurnId(through));
  }

  private static InferenceContext everything() {
    return new InferenceContext(
        List.of(
            new Summary(chapter(1, 2), "first chapter"), new Summary(chapter(3, 4), "second one")),
        List.of(turn(5), turn(6)),
        List.of(Memory.text("recall", "remembered")),
        List.of(State.text("plan", "step one"), State.text("plan", "step two")),
        turn(7),
        List.of(Ambient.text("clock", "noon")));
  }

  private DefaultInferenceService service(
      InferenceContext context, InferenceProvider provider, Optional<JsonSchema> shape) {
    return new DefaultInferenceService(
        invocation -> context,
        provider,
        new SystemPrompt("You are terse."),
        TOOLS,
        Narrator.silent(),
        shape,
        payloads);
  }

  private static InferenceInvocation invocation(boolean answerOnly) {
    return new InferenceInvocation(TYPE, AGENT, OPTIONS, answerOnly);
  }

  private List<Block> blocks(PayloadRef ref) {
    return switch (payloads.forAgent(AGENT).get(ref)) {
      case Payloads.Resolved.Found(List<Block> found) -> found;
      case Payloads.Resolved.Missing() -> throw new AssertionError("nothing stored at " + ref);
    };
  }

  private String text(PayloadRef ref) {
    List<Block> found = blocks(ref);
    assertThat(found).singleElement().isInstanceOf(Block.Text.class);
    return ((Block.Text) found.getFirst()).text();
  }

  private String document(PayloadRef ref) {
    return payloads.forAgent(AGENT).getDocument(ref).toString();
  }

  private String toolsDocumentOf(InferenceRequest request) {
    List<String> offers =
        request.toolset().offers().stream()
            .map(
                offer ->
                    "{\"name\":\"%s\",\"description\":\"%s\",\"schema\":%s}"
                        .formatted(
                            offer.name().value(),
                            offer.description(),
                            MAPPER.readTree(offer.schema().json()).toString()))
            .toList();
    return "{\"offers\":[%s],\"choice\":%s}"
        .formatted(String.join(",", offers), MAPPER.valueToTree(request.toolset().choice()));
  }

  @Test
  void the_manifest_resolves_to_exactly_what_was_sent() {
    Inferred inferred =
        service(everything(), recording, Optional.of(ANSWER_SCHEMA)).infer(invocation(false));
    InferenceRequest sent = received.getFirst();
    InferenceRequestManifest manifest = inferred.manifest();

    assertThat(text(manifest.instructions())).isEqualTo(sent.systemPrompt().value());
    assertThat(document(manifest.tools())).isEqualTo(toolsDocumentOf(sent));
    assertThat(document(manifest.answerShape().orElseThrow()))
        .isEqualTo(MAPPER.readTree(sent.outputSchema().orElseThrow().json()).toString());
    assertThat(document(manifest.options()))
        .isEqualTo(
            "{\"model\":\"a-model\",\"maxTokens\":4096,"
                + "\"properties\":{\"vendor.alpha\":\"2\",\"vendor.zeta\":\"1\"}}");
    assertThat(sent.context().summaries()).isNotEmpty();
    assertThat(manifest.summarizedThrough())
        .hasValue(sent.context().summaries().getLast().chapter().through());
    assertThat(manifest.tail())
        .hasValue(new InferenceRequestManifest.TurnRange(new TurnId(5), new TurnId(6)));
    assertThat(manifest.memory()).hasSameSizeAs(sent.context().memory());
    assertThat(manifest.state()).hasSameSizeAs(sent.context().state());
    assertThat(manifest.ambient()).hasSameSizeAs(sent.context().ambient());
    assertThat(manifest.memory().getFirst().kind()).isEqualTo("recall");
    assertThat(blocks(manifest.memory().getFirst().content()))
        .isEqualTo(List.copyOf(sent.context().memory().getFirst().content()));
    assertThat(manifest.ambient().getFirst().kind()).isEqualTo("clock");
    assertThat(blocks(manifest.ambient().getFirst().content()))
        .isEqualTo(List.copyOf(sent.context().ambient().getFirst().content()));
    for (int i = 0; i < manifest.state().size(); i++) {
      assertThat(manifest.state().get(i).kind()).isEqualTo(sent.context().state().get(i).kind());
      assertThat(blocks(manifest.state().get(i).content()))
          .isEqualTo(List.copyOf(sent.context().state().get(i).content()));
    }
  }

  @Test
  void the_provider_is_handed_the_request_the_manifest_describes() {
    Inferred inferred =
        service(everything(), recording, Optional.of(ANSWER_SCHEMA)).infer(invocation(false));

    assertThat(received).hasSize(1);
    assertThat(InferenceRequestManifests.of(received.getFirst(), payloads.forAgent(AGENT)))
        .isEqualTo(inferred.manifest());
    assertThat(inferred.result()).isInstanceOf(InferenceResult.Answer.class);
  }

  @Test
  void an_answer_only_call_names_different_tools() {
    DefaultInferenceService service = service(everything(), recording, Optional.empty());

    InferenceRequestManifest ordinary = service.infer(invocation(false)).manifest();
    InferenceRequestManifest answering = service.infer(invocation(true)).manifest();

    assertThat(answering.tools()).isNotEqualTo(ordinary.tools());
    assertThat(document(answering.tools())).contains("\"choice\":{\"type\":\"answer\"}");
    assertThat(document(ordinary.tools())).contains("\"choice\":{\"type\":\"auto\"}");
    assertThat(answering.instructions()).isEqualTo(ordinary.instructions());
    assertThat(answering.options()).isEqualTo(ordinary.options());
  }

  @Test
  void the_options_document_does_not_depend_on_the_order_properties_were_given() {
    Map<String, String> reversed = new LinkedHashMap<>();
    reversed.put("vendor.alpha", "2");
    reversed.put("vendor.zeta", "1");
    DefaultInferenceService service = service(everything(), recording, Optional.empty());

    InferenceRequestManifest given =
        service.infer(new InferenceInvocation(TYPE, AGENT, OPTIONS, false)).manifest();
    InferenceRequestManifest other =
        service
            .infer(
                new InferenceInvocation(
                    TYPE, AGENT, new InferenceOptions("a-model", 4096, reversed), false))
            .manifest();

    assertThat(other.options()).isEqualTo(given.options());
    assertThat(document(given.options()))
        .contains("\"properties\":{\"vendor.alpha\":\"2\",\"vendor.zeta\":\"1\"}");
  }

  @Test
  void a_request_with_no_tail_no_summaries_and_no_sections_has_an_empty_manifest_for_them() {
    InferenceContext bare = InferenceContext.of(List.of(turn(1)));

    InferenceRequestManifest manifest =
        service(bare, recording, Optional.empty()).infer(invocation(false)).manifest();

    assertThat(manifest.tail()).isEmpty();
    assertThat(manifest.summarizedThrough()).isEmpty();
    assertThat(manifest.memory()).isEmpty();
    assertThat(manifest.state()).isEmpty();
    assertThat(manifest.ambient()).isEmpty();
    assertThat(manifest.answerShape()).isEmpty();
  }

  @Test
  void sections_are_listed_in_the_order_their_sources_were_bound() {
    InferenceRequestManifest manifest =
        service(everything(), recording, Optional.empty()).infer(invocation(false)).manifest();

    assertThat(manifest.state()).hasSize(2);
    assertThat(manifest.state())
        .extracting(InferenceRequestManifest.Section::kind)
        .containsExactly("plan", "plan");
    assertThat(text(manifest.state().get(0).content())).isEqualTo("step one");
    assertThat(text(manifest.state().get(1).content())).isEqualTo("step two");
  }

  @Test
  void the_manifest_names_the_engines_version() {
    InferenceRequestManifest manifest =
        service(everything(), recording, Optional.empty()).infer(invocation(false)).manifest();

    assertThat(manifest.engineVersion()).isEqualTo(EngineVersion.current());
  }

  @Test
  void a_provider_that_throws_returns_no_manifest() {
    IllegalStateException boom = new IllegalStateException("the wire is down");
    AtomicBoolean instructionsWereStored = new AtomicBoolean();
    PayloadRef instructions =
        new InMemoryPayloads(new JacksonCodecFactory(MAPPER))
            .forAgent(AGENT)
            .put(List.of(new Block.Text("You are terse.")));
    InferenceProvider failing =
        (request, narrator) -> {
          instructionsWereStored.set(
              payloads.forAgent(AGENT).get(instructions) instanceof Payloads.Resolved.Found);
          throw boom;
        };
    DefaultInferenceService service = service(everything(), failing, Optional.empty());
    InferenceInvocation invocation = invocation(false);

    assertThatThrownBy(() -> service.infer(invocation)).isSameAs(boom);
    assertThat(instructionsWereStored).isTrue();
  }
}
