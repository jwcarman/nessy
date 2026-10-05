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

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.event.RequestManifest;
import org.jwcarman.nessy.backend.event.RequestManifest.Section;
import org.jwcarman.nessy.backend.event.RequestManifest.TurnRange;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a request to the model is made of, each part stored and named by reference.
 *
 * <p><b>Every part is put every time.</b> Nothing here remembers what was stored before: a
 * payload's reference is a hash of its content, so a part that has not changed is the same
 * reference and the store writes nothing new. The cost is a few small statements for each model
 * call.
 *
 * <p><b>Field order is fixed,</b> the order each document is built in below, so the same request
 * always yields the same references. The bytes that are hashed are the payload store's own: the
 * mapper here only builds the trees.
 */
final class RequestManifests {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private RequestManifests() {}

  static RequestManifest of(InferenceRequest request, Payloads payloads) {
    return new RequestManifest(
        EngineVersion.current(),
        payloads.put(List.of(new Block.Text(request.systemPrompt().value()))),
        payloads.putDocument(tools(request.toolset())),
        request.outputSchema().map(schema -> payloads.putDocument(parse(schema.json()))),
        payloads.putDocument(options(request.options())),
        summarizedThrough(request.context().summaries()),
        tail(request.context().tail()),
        sections(request.context().memory(), Memory::kind, Memory::content, payloads),
        sections(request.context().state(), State::kind, State::content, payloads),
        sections(request.context().ambient(), Ambient::kind, Ambient::content, payloads));
  }

  /** The offers in the order they were bound, and the choice this call makes among them. */
  private static JsonNode tools(Toolset toolset) {
    ObjectNode document = MAPPER.createObjectNode();
    ArrayNode offers = document.putArray("offers");
    for (ToolOffer offer : toolset.offers()) {
      ObjectNode entry = offers.addObject();
      entry.put("name", offer.name().value());
      entry.put("description", offer.description());
      entry.set("schema", parse(offer.schema().json()));
    }
    // Through the mapper, which keeps the discriminator that says which kind of choice it is.
    document.set("choice", MAPPER.valueToTree(toolset.choice()));
    return document;
  }

  /**
   * The model, the limits and the vendor properties. The properties are written with their keys in
   * sorted order, so a caller's {@code Map.of(...)} cannot change the reference between JVM runs.
   */
  private static JsonNode options(InferenceOptions options) {
    ObjectNode document = MAPPER.createObjectNode();
    document.put("model", options.modelName());
    document.put("maxTokens", options.maxTokens());
    ObjectNode properties = document.putObject("properties");
    // Sorted by key, so the order a caller's map happens to iterate in cannot change the reference.
    new TreeMap<>(options.properties()).forEach(properties::put);
    return document;
  }

  /** The last turn the summaries shown cover: they are written once, so it names them all. */
  private static Optional<TurnId> summarizedThrough(List<Summary> summaries) {
    if (summaries.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(summaries.getLast().chapter().through());
  }

  private static Optional<TurnRange> tail(List<Turn> tail) {
    if (tail.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new TurnRange(tail.getFirst().id(), tail.getLast().id()));
  }

  /** One section for each of the context's, in its order: kinds are not unique. */
  private static <S> List<Section> sections(
      List<S> sections,
      Function<S, String> kind,
      Function<S, ? extends List<? extends Block>> content,
      Payloads payloads) {
    return sections.stream()
        .map(section -> new Section(kind.apply(section), payloads.put(content.apply(section))))
        .toList();
  }

  private static JsonNode parse(String json) {
    return MAPPER.readTree(json);
  }
}
