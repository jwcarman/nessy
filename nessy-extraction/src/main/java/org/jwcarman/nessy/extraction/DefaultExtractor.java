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
package org.jwcarman.nessy.extraction;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Extraction;
import org.jwcarman.nessy.api.Extractor;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.ToolChoice;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a document for its fields, with a model that has been given nothing to act with.
 *
 * <p>One call, no tools it can run, no memory, no history: a model is shown a document and a shape,
 * and the only thing it can do is fill the shape in. That is the whole of the isolation -- an
 * invoice number and an amount cannot carry out an instruction, whatever the document asked for.
 *
 * <p><b>Some tool rather than this tool.</b> Exactly one is offered, so "call one of them" and
 * "call this one" ask for the same thing -- and only the first is a string every OpenAI-compatible
 * server understands. Naming it sends an object that LM Studio and its like reject outright, which
 * is a 400 rather than a worse answer.
 *
 * <p><b>The shape is a tool that never runs.</b> The document is untrusted, so the model is offered
 * exactly one tool, required to call it, and its arguments are the answer. Nothing is dispatched;
 * what was wanted was the shape of the tool's input rather than the work behind it. Every vendor
 * can require a named tool on its stable API, which is why this rather than a response schema.
 *
 * <p><b>Structured is not sanitised.</b> A schema constrains the shape, not the content: a {@code
 * String} field will carry whatever text the document put in it, injection included. The safety
 * comes from the fields being as narrow as the job allows -- an enum rather than a string, a number
 * rather than a number written out -- and from what the privileged side does with them afterwards.
 * An extractor makes untrusted text into data; it does not make it harmless.
 *
 * <p>Deliberately not an agent. There is nothing to remember between documents, nothing to approve
 * and nothing to come back to, so there is no turn, no row and no fold -- just a call that blocks
 * and answers.
 */
public final class DefaultExtractor implements Extractor {

  private final InferenceProvider provider;
  private final InferenceOptions options;
  private final InputSchemaGenerator schemas;
  private final ObjectMapper mapper;
  private final SystemPrompt systemPrompt;
  private final ToolName toolName;

  DefaultExtractor(
      InferenceProvider provider,
      InputSchemaGenerator schemas,
      ObjectMapper mapper,
      SystemPrompt systemPrompt,
      ToolName toolName,
      InferenceOptions options) {
    this.provider = provider;
    this.schemas = schemas;
    this.mapper = mapper;
    this.systemPrompt = systemPrompt;
    this.toolName = toolName;
    this.options = options;
  }

  /**
   * Reads one document for the fields {@code type} declares.
   *
   * <p>Blocks, because there is nothing to wait for but the call itself. Returns rather than throws
   * on a refusal: with untrusted input, a model declining to read a document is an outcome worth
   * branching on.
   *
   * @param type the shape to fill in, whose schema is what the model is shown
   * @param document the untrusted text, shown as content and never as instruction
   */
  @Override
  public <T> Extraction<T> extract(Class<T> type, String document) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(document, "document must not be null");

    InferenceResult result = provider.infer(requestFor(type, document));
    return switch (result) {
      case InferenceResult.Actions(List<Block.ActionRequestContent> blocks, Usage usage) ->
          recorded(type, blocks, usage);
      case InferenceResult.Refusal(String category, Usage usage) ->
          new Extraction.Refused<>(category, usage);
      case InferenceResult.Fault(Failure failure, Usage usage) ->
          new Extraction.Failed<>(failure.reason(), usage);
      // Asked for a shape and given prose. Not rare: requiring a tool is a request, and a
      // document with none of the fields in it draws an explanation instead -- which is the
      // useful answer, because it says what was missing.
      case InferenceResult.Answer(List<Block.AnswerContent> blocks, Usage usage) ->
          new Extraction.Talked<>(said(blocks), usage);
    };
  }

  private InferenceRequest requestFor(Class<?> type, String document) {
    return new InferenceRequest(
        systemPrompt,
        InferenceContext.of(List.of(asOneTurn(document))),
        List.of(new ToolOffer(toolName, describing(type), schemas.generate(type))),
        new ToolChoice.Any(),
        options);
  }

  /**
   * What the recording tool says it is for, with the shape named in words.
   *
   * <p>The name is an identifier and has a vendor's length limit on it; the description is prose
   * and does not, so this is where the shape gets spelled out. A model reads the second and matches
   * on the first.
   */
  private static String describing(Class<?> type) {
    return "Records the fields of a %s found in the document.".formatted(type.getSimpleName());
  }

  /** The document as the only thing that was ever said, because it is. */
  private static Turn asOneTurn(String document) {
    return new Turn(
        new TurnId(1),
        new Observation(new Seq(1), List.of(new Block.Text(document))),
        List.of(),
        null,
        0);
  }

  /**
   * The recorded fields, from the call the model was required to make.
   *
   * <p>{@link InferenceResult.Actions} refuses to exist without at least one call, so there is one
   * to take rather than a case to handle.
   */
  private <T> Extraction<T> recorded(
      Class<T> type, List<Block.ActionRequestContent> blocks, Usage usage) {
    Block.ToolCall call =
        blocks.stream()
            .filter(Block.ToolCall.class::isInstance)
            .map(Block.ToolCall.class::cast)
            .findFirst()
            .orElseThrow();
    return read(type, call, usage);
  }

  private <T> Extraction<T> read(Class<T> type, Block.ToolCall call, Usage usage) {
    try {
      return new Extraction.Extracted<>(mapper.readValue(call.arguments(), type), usage);
    } catch (RuntimeException e) {
      // The schema was what the model was shown, so arguments that will not read as the type are
      // the model's answer being wrong rather than the caller's type being wrong. Reported as a
      // failure, with what it said kept out of the message: it is untrusted text.
      return new Extraction.Failed<>(
          "the recorded fields could not be read as " + type.getSimpleName(), usage);
    }
  }

  private static String said(List<Block.AnswerContent> blocks) {
    return blocks.stream()
        .filter(Block.Text.class::isInstance)
        .map(Block.Text.class::cast)
        .map(Block.Text::text)
        .findFirst()
        .orElse("");
  }
}
