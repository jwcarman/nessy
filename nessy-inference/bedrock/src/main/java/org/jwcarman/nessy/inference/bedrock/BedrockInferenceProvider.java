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
package org.jwcarman.nessy.inference.bedrock;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.InternalServerException;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import tools.jackson.databind.json.JsonMapper;

/**
 * Amazon Bedrock, through the AWS SDK for Java v2's Converse API.
 *
 * <p>One adapter covers Claude, Nova, Llama, Mistral and the rest of the Bedrock catalog, because
 * Converse is model-agnostic on the wire. Owns the client and is the only class here that touches
 * the network; the projection onto the wire lives in {@link BedrockRequests} and can be tested
 * without a credential.
 *
 * <p><b>Holds no model name.</b> Which model to call travels in {@link InferenceOptions}, so one
 * client serves several agent types asking for different models.
 *
 * <p><b>Streams, and narrates as it goes.</b> Every call is a {@code ConverseStream}; each text
 * delta is narrated as a {@link AgentEvent.ContentDelta} and each reasoning delta as a {@link
 * AgentEvent.ThinkingDelta} the moment it arrives. The AWS SDK has no accumulator, so the events
 * are folded back into one response here -- text per block, a tool call's JSON input assembled
 * across its deltas and parsed once the block closes, reasoning with its signature -- and the reply
 * is read from that exactly as a non-streaming one would be. The engine receives one result.
 *
 * <p><b>Reasoning round-trips.</b> A model that reasons on this wire (Claude with extended thinking
 * on) returns reasoning content with a signature, and wants it back untouched on the next turn. It
 * comes back as a {@link Block.Provider} block carrying this vendor's tag and goes out again as it
 * came.
 */
public final class BedrockInferenceProvider implements InferenceProvider, AutoCloseable {

  /**
   * The OpenTelemetry GenAI semantic conventions' value for this vendor, and the tag on every
   * {@link Block.Provider} block this adapter issues.
   */
  static final String PROVIDER_NAME = "aws.bedrock";

  private static final String NAME = "Bedrock";
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int SERVER_ERRORS = 500;
  private static final String CALL_FAILED = "model call failed: ";

  private final BedrockClient client;
  private final JsonMapper mapper;

  BedrockInferenceProvider(BedrockClient client, JsonMapper mapper) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  public static BedrockInferenceProvider fromEnv() {
    return create(BedrockProviderConfig::fromEnv);
  }

  public static BedrockInferenceProvider create(BedrockProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    BedrockProviderConfig config = new BedrockProviderConfig();
    customizer.customize(config);
    return config.build();
  }

  /**
   * Total for anything the provider can do to us. A classified failure comes back as {@link
   * InferenceResult.Fault} rather than thrown, because the agent that asked for this is mid-turn
   * and only something reaching the fold ends that turn.
   *
   * <p>{@link SdkException} is the root of everything the AWS SDK throws; catching it is what makes
   * the handling total. A bug in this adapter still escapes rather than being recorded as the
   * model's fault.
   */
  @Override
  public String providerName() {
    return PROVIDER_NAME;
  }

  @Override
  public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
    Objects.requireNonNull(narrator, "narrator must not be null");
    try {
      Folded folded = new Folded(narrator, mapper);
      client.converseStream(BedrockRequests.toRequest(request, mapper), folded);
      if (!folded.any) {
        return new InferenceResult.Fault(new Failure.Permanent("model returned no reply"));
      }
      if (folded.stop == null) {
        // The stream closed before messageStop: half a reply, which the story could not hold.
        return new InferenceResult.Fault(
            new Failure.Permanent("the stream ended before the answer was complete"));
      }
      ConverseResponse response = folded.response();
      return read(response).withUsage(usageOf(response));
    } catch (SdkException e) {
      return new InferenceResult.Fault(classify(e));
    }
  }

  private static Usage usageOf(ConverseResponse response) {
    TokenUsage counted = response.usage();
    if (counted == null || counted.inputTokens() == null || counted.outputTokens() == null) {
      return Usage.unknown();
    }
    return new Usage(counted.inputTokens(), counted.outputTokens());
  }

  /**
   * The stream's events folded into one response, and narrated on the way.
   *
   * <p>Blocks are kept by index, because that is how the wire addresses them: a text block is its
   * deltas joined; a tool-use block is its start's id and name and its deltas' JSON joined and
   * parsed when the block closes; a reasoning block is its text joined, with whatever signature or
   * redacted bytes arrived. A block still open at messageStop is closed then.
   */
  static final class Folded implements Consumer<ConverseStreamOutput> {
    private final AgentNarrator narrator;
    private final JsonMapper mapper;
    private final SortedMap<Integer, Pending> open = new TreeMap<>();
    private final SortedMap<Integer, ContentBlock> closed = new TreeMap<>();
    private StopReason stop;
    private TokenUsage usage;
    private boolean any;

    Folded(AgentNarrator narrator, JsonMapper mapper) {
      this.narrator = narrator;
      this.mapper = mapper;
    }

    @Override
    public void accept(ConverseStreamOutput event) {
      any = true;
      switch (event) {
        case ContentBlockStartEvent start -> started(start);
        case ContentBlockDeltaEvent delta -> delta(delta);
        case ContentBlockStopEvent stopped -> close(stopped.contentBlockIndex());
        case MessageStopEvent stopped -> {
          stop = stopped.stopReason();
          List.copyOf(open.keySet()).forEach(this::close);
        }
        case ConverseStreamMetadataEvent metadata -> usage = metadata.usage();
        default -> {
          // messageStart and metadata: nothing in them is read.
        }
      }
    }

    private void started(ContentBlockStartEvent event) {
      Pending pending = open.computeIfAbsent(event.contentBlockIndex(), _ -> new Pending());
      if (event.start() != null && event.start().toolUse() != null) {
        pending.toolUseId = event.start().toolUse().toolUseId();
        pending.toolName = event.start().toolUse().name();
      }
    }

    private void delta(ContentBlockDeltaEvent event) {
      Pending pending = open.computeIfAbsent(event.contentBlockIndex(), _ -> new Pending());
      ContentBlockDelta delta = event.delta();
      if (delta.text() != null) {
        pending.text.append(delta.text());
        if (!delta.text().isEmpty()) {
          narrator.narrate(new AgentEvent.ContentDelta(delta.text()));
        }
      }
      if (delta.toolUse() != null && delta.toolUse().input() != null) {
        pending.toolInput.append(delta.toolUse().input());
      }
      if (delta.reasoningContent() != null) {
        ReasoningContentBlockDelta reasoning = delta.reasoningContent();
        if (reasoning.text() != null) {
          pending.reasoning.append(reasoning.text());
          if (!reasoning.text().isEmpty()) {
            narrator.narrate(new AgentEvent.ThinkingDelta(reasoning.text()));
          }
        }
        if (reasoning.signature() != null) {
          pending.signature = reasoning.signature();
        }
        if (reasoning.redactedContent() != null) {
          pending.redacted = reasoning.redactedContent();
        }
      }
    }

    private void close(Integer index) {
      Pending pending = open.remove(index);
      if (pending != null) {
        closed.put(index, pending.block(mapper));
      }
    }

    ConverseResponse response() {
      return ConverseResponse.builder()
          .stopReason(stop)
          .usage(usage)
          .output(
              ConverseOutput.fromMessage(
                  Message.builder()
                      .role(ConversationRole.ASSISTANT)
                      .content(List.copyOf(closed.values()))
                      .build()))
          .build();
    }
  }

  /** One block under way: whichever of its three shapes the deltas turn out to fill. */
  private static final class Pending {
    final StringBuilder text = new StringBuilder();
    final StringBuilder toolInput = new StringBuilder();
    final StringBuilder reasoning = new StringBuilder();
    String toolUseId;
    String toolName;
    String signature;
    SdkBytes redacted;

    ContentBlock block(JsonMapper mapper) {
      if (toolUseId != null) {
        Object input =
            toolInput.isEmpty() ? Map.of() : mapper.readValue(toolInput.toString(), Object.class);
        return ContentBlock.fromToolUse(
            ToolUseBlock.builder()
                .toolUseId(toolUseId)
                .name(toolName)
                .input(BedrockRequests.document(input))
                .build());
      }
      if (redacted != null) {
        return ContentBlock.fromReasoningContent(
            ReasoningContentBlock.fromRedactedContent(redacted));
      }
      if (!reasoning.isEmpty() || signature != null) {
        return ContentBlock.fromReasoningContent(
            ReasoningContentBlock.fromReasoningText(
                ReasoningTextBlock.builder()
                    .text(reasoning.toString())
                    .signature(signature)
                    .build()));
      }
      return ContentBlock.fromText(text.toString());
    }
  }

  /**
   * Which of the three shapes a reply is.
   *
   * <p>A guardrail and a content filter are the provider stopping the response, reported in the
   * stop reason and taken where they are offered as a refusal named by the vendor's own value.
   * Otherwise the choice is made on the presence of a tool-use block, as on every wire that can
   * carry one.
   */
  private InferenceResult read(ConverseResponse response) {
    StopReason stop = response.stopReason();
    if (stop == StopReason.GUARDRAIL_INTERVENED || stop == StopReason.CONTENT_FILTERED) {
      return new InferenceResult.Refusal(response.stopReasonAsString());
    }
    List<ContentBlock> content = contentOf(response);
    boolean asking = content.stream().anyMatch(block -> block.toolUse() != null);

    List<Block> blocks = new ArrayList<>();
    for (ContentBlock block : content) {
      add(block, asking, blocks);
    }

    if (!asking) {
      if (blocks.isEmpty()) {
        // A reply with nothing in it: a model that spent its budget thinking, say. Said as a
        // fault rather than an answer of no blocks, which the story could not hold.
        return new InferenceResult.Fault(
            new Failure.Permanent(
                "model returned an empty answer (stop_reason="
                    + response.stopReasonAsString()
                    + ")"));
      }
      return new InferenceResult.Answer(
          blocks.stream().map(Block.AnswerContent.class::cast).toList());
    }
    return new InferenceResult.Actions(
        blocks.stream().map(Block.ActionRequestContent.class::cast).toList());
  }

  private static List<ContentBlock> contentOf(ConverseResponse response) {
    if (response.output() == null || response.output().message() == null) {
      return List.of();
    }
    return response.output().message().content();
  }

  /**
   * One block's blocks. Images, documents and citations: nothing here asked for any of them, so
   * nothing here knows what to do with one. Dropped rather than guessed at.
   */
  private void add(ContentBlock block, boolean asking, List<Block> blocks) {
    if (block.text() != null && !block.text().isBlank()) {
      blocks.add(asking ? new Block.Commentary(block.text()) : new Block.Text(block.text()));
    }
    if (block.toolUse() != null) {
      ToolUseBlock use = block.toolUse();
      blocks.add(new Block.ToolCall(use.toolUseId(), use.name(), arguments(use)));
    }
    if (block.reasoningContent() != null) {
      blocks.add(reasoning(block.reasoningContent()));
    }
  }

  /**
   * The call's arguments as JSON text. {@code Document.unwrap()} yields plain maps, lists, strings,
   * numbers and booleans, which is what this project's Jackson writes without knowing the SDK.
   */
  private String arguments(ToolUseBlock use) {
    return mapper.writeValueAsString(use.input() == null ? Map.of() : use.input().unwrap());
  }

  /** Bedrock's own reasoning shape, kept whole: this adapter is the only thing that reads it. */
  private Block.Provider reasoning(ReasoningContentBlock reasoning) {
    Map<String, Object> payload;
    if (reasoning.reasoningText() != null) {
      payload =
          Map.of(
              "type",
              "reasoning",
              "text",
              String.valueOf(reasoning.reasoningText().text()),
              "signature",
              reasoning.reasoningText().signature() == null
                  ? ""
                  : reasoning.reasoningText().signature());
    } else {
      payload =
          Map.of(
              "type",
              "redacted",
              "data",
              reasoning.redactedContent() == null
                  ? ""
                  : Base64.getEncoder().encodeToString(reasoning.redactedContent().asByteArray()));
    }
    return new Block.Provider(PROVIDER_NAME, mapper.writeValueAsString(payload));
  }

  /**
   * Decides what a failed call means, which is the one thing this class knows and nothing above it
   * does.
   *
   * <p>Throttling, a 5xx, and the service's own "not ready" and "timed out" are its admission that
   * trying again may work. A client-side failure is unknown: the request may or may not have been
   * processed before the connection went, so repeating it is safe exactly when repeating the work
   * is safe, and that is not this class's call. Everything else is a request the service will keep
   * refusing.
   *
   * <p><b>Nothing is classified {@link Failure.Rejected}.</b> That is the one classification that
   * authorises throwing away something a person said.
   */
  private static Failure classify(SdkException e) {
    if (e instanceof ThrottlingException
        || e instanceof ServiceUnavailableException
        || e instanceof InternalServerException
        || e instanceof ModelTimeoutException
        || e instanceof ModelNotReadyException) {
      return new Failure.Transient(CALL_FAILED + e.getMessage());
    }
    if (e instanceof AwsServiceException service
        && (service.isThrottlingException()
            || service.statusCode() == TOO_MANY_REQUESTS
            || service.statusCode() >= SERVER_ERRORS)) {
      return new Failure.Transient(CALL_FAILED + e.getMessage());
    }
    if (e instanceof SdkClientException) {
      return new Failure.Unknown("no answer from the model: " + e.getMessage());
    }
    return new Failure.Permanent(CALL_FAILED + e.getMessage());
  }

  /**
   * Closes the client this provider BUILT; one handed in through the config is never closed here.
   */
  @Override
  public void close() {
    client.close();
  }

  /** This vendor, by name -- not an SPI method, kept because callers and logs want it. */
  public String name() {
    return NAME;
  }
}
