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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.Usage;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The Bedrock provider")
class BedrockInferenceProviderTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static InferenceRequest request() {
    Turn open =
        new Turn(
            new TurnId(1),
            new Observation(new Seq(1), List.of(new Block.Text("hi"))),
            List.of(),
            null,
            0);
    return new InferenceRequest(
        new SystemPrompt("be brief"),
        InferenceContext.of(List.of(open)),
        List.of(),
        new InferenceOptions("us.amazon.nova-lite", 256));
  }

  private static ConverseResponse reply(StopReason stop, ContentBlock... content) {
    return ConverseResponse.builder()
        .stopReason(stop)
        .output(
            ConverseOutput.fromMessage(
                Message.builder().role(ConversationRole.ASSISTANT).content(content).build()))
        .build();
  }

  /**
   * The events a service would have streamed this reply as: messageStart; for each block, a start
   * when it is a tool use, its text or reasoning or JSON input five characters at a time, and a
   * stop; then messageStop with the stop reason.
   */
  static List<ConverseStreamOutput> eventsOf(ConverseResponse response) {
    List<ConverseStreamOutput> events = new ArrayList<>();
    events.add(MessageStartEvent.builder().role(ConversationRole.ASSISTANT).build());
    List<ContentBlock> content =
        response.output() == null || response.output().message() == null
            ? List.of()
            : response.output().message().content();
    for (int i = 0; i < content.size(); i++) {
      ContentBlock block = content.get(i);
      List<ContentBlockDelta> deltas = new ArrayList<>();
      if (block.toolUse() != null) {
        ToolUseBlock use = block.toolUse();
        events.add(
            ContentBlockStartEvent.builder()
                .contentBlockIndex(i)
                .start(
                    ContentBlockStart.fromToolUse(
                        b -> b.toolUseId(use.toolUseId()).name(use.name())))
                .build());
        String json =
            MAPPER.writeValueAsString(use.input() == null ? Map.of() : use.input().unwrap());
        pieces(json)
            .forEach(piece -> deltas.add(ContentBlockDelta.fromToolUse(b -> b.input(piece))));
      } else if (block.reasoningContent() != null) {
        ReasoningContentBlock reasoning = block.reasoningContent();
        if (reasoning.reasoningText() != null) {
          pieces(reasoning.reasoningText().text())
              .forEach(
                  piece ->
                      deltas.add(
                          ContentBlockDelta.fromReasoningContent(
                              ReasoningContentBlockDelta.fromText(piece))));
          if (reasoning.reasoningText().signature() != null) {
            deltas.add(
                ContentBlockDelta.fromReasoningContent(
                    ReasoningContentBlockDelta.fromSignature(
                        reasoning.reasoningText().signature())));
          }
        } else {
          deltas.add(
              ContentBlockDelta.fromReasoningContent(
                  ReasoningContentBlockDelta.fromRedactedContent(reasoning.redactedContent())));
        }
      } else {
        pieces(block.text()).forEach(piece -> deltas.add(ContentBlockDelta.fromText(piece)));
      }
      for (ContentBlockDelta delta : deltas) {
        events.add(ContentBlockDeltaEvent.builder().contentBlockIndex(i).delta(delta).build());
      }
      events.add(ContentBlockStopEvent.builder().contentBlockIndex(i).build());
    }
    events.add(MessageStopEvent.builder().stopReason(response.stopReason()).build());
    if (response.usage() != null) {
      events.add(ConverseStreamMetadataEvent.builder().usage(response.usage()).build());
    }
    return events;
  }

  /** Five characters at a time, so a stream of them is several events. */
  private static List<String> pieces(String text) {
    List<String> pieces = new ArrayList<>();
    for (int i = 0; i < text.length(); i += 5) {
      pieces.add(text.substring(i, Math.min(text.length(), i + 5)));
    }
    return pieces;
  }

  private static InferenceResult infer(ConverseResponse response) {
    return new BedrockInferenceProvider(new ScriptedClient(response, null), MAPPER)
        .infer(request());
  }

  private static Failure inferFailing(RuntimeException failure) {
    InferenceResult result =
        new BedrockInferenceProvider(new ScriptedClient(null, failure), MAPPER).infer(request());
    assertThat(result).isInstanceOf(InferenceResult.Fault.class);
    return ((InferenceResult.Fault) result).failure();
  }

  private record ScriptedClient(
      ConverseResponse response, RuntimeException failure, AtomicBoolean closed)
      implements BedrockClient {

    ScriptedClient(ConverseResponse response, RuntimeException failure) {
      this(response, failure, new AtomicBoolean());
    }

    /** The reply cut into the events the service would have streamed it as. */
    @Override
    public void converseStream(
        ConverseStreamRequest request, Consumer<ConverseStreamOutput> onEvent) {
      if (failure != null) {
        throw failure;
      }
      eventsOf(response).forEach(onEvent);
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }

  @Nested
  class WhatItCost {

    @Test
    void the_metadata_events_usage_is_the_results() {
      ConverseResponse priced =
          reply(StopReason.END_TURN, ContentBlock.fromText("hello")).toBuilder()
              .usage(TokenUsage.builder().inputTokens(4).outputTokens(6).totalTokens(10).build())
              .build();

      assertThat(infer(priced).usage()).isEqualTo(new Usage(4, 6));
      assertThat(infer(reply(StopReason.END_TURN, ContentBlock.fromText("hello"))).usage())
          .isEqualTo(Usage.unknown());
    }
  }

  @Nested
  class WhatIsNarrated {

    private final List<AgentEvent> narrated = new ArrayList<>();

    private InferenceResult inferNarrating(BedrockClient client) {
      return new BedrockInferenceProvider(client, MAPPER).infer(request(), narrated::add);
    }

    @Test
    void text_and_reasoning_are_narrated_as_they_arrive_and_the_reply_is_read_whole() {
      ConverseResponse response =
          reply(
              StopReason.END_TURN,
              ContentBlock.fromReasoningContent(
                  ReasoningContentBlock.fromReasoningText(
                      ReasoningTextBlock.builder().text("hmm, a lake").signature("sig").build())),
              ContentBlock.fromText("a lake monster"));

      InferenceResult result = inferNarrating(new ScriptedClient(response, null));

      assertThat(narrated)
          .containsExactly(
              new AgentEvent.ThinkingDelta("hmm, "),
              new AgentEvent.ThinkingDelta("a lak"),
              new AgentEvent.ThinkingDelta("e"),
              new AgentEvent.ContentDelta("a lak"),
              new AgentEvent.ContentDelta("e mon"),
              new AgentEvent.ContentDelta("ster"));
      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(((InferenceResult.Answer) result).blocks())
          .contains(new Block.Text("a lake monster"));
    }

    @Test
    void tool_input_fragments_are_not_narrated_and_the_input_is_parsed_once_whole() {
      ConverseResponse response =
          reply(
              StopReason.TOOL_USE,
              ContentBlock.fromToolUse(
                  ToolUseBlock.builder()
                      .toolUseId("tooluse_1")
                      .name("depth")
                      .input(Document.fromMap(Map.of("lake", Document.fromString("ness"))))
                      .build()));

      InferenceResult result = inferNarrating(new ScriptedClient(response, null));

      assertThat(narrated).isEmpty();
      assertThat(result)
          .isEqualTo(
              new InferenceResult.Actions(
                  List.of(new Block.ToolCall("tooluse_1", "depth", "{\"lake\":\"ness\"}"))));
    }

    @Test
    void a_stream_of_nothing_and_a_stream_cut_short_are_both_faults() {
      BedrockClient silent = new ScriptedEvents(List.of());
      assertThat(inferNarrating(silent))
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> assertThat(fault.failure().reason()).contains("no reply"));

      List<ConverseStreamOutput> cut =
          eventsOf(reply(StopReason.END_TURN, ContentBlock.fromText("a lake monster")));
      BedrockClient early = new ScriptedEvents(cut.subList(0, cut.size() - 1));
      assertThat(inferNarrating(early))
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> assertThat(fault.failure().reason()).contains("ended before"));
    }
  }

  /** A client that streams exactly these events. */
  private record ScriptedEvents(List<ConverseStreamOutput> events) implements BedrockClient {
    @Override
    public void converseStream(
        ConverseStreamRequest request, Consumer<ConverseStreamOutput> onEvent) {
      events.forEach(onEvent);
    }

    @Override
    public void close() {
      // Nothing to close.
    }
  }

  @Nested
  class WhatComesBack {

    @Test
    void prose_alone_is_an_answer() {
      assertThat(infer(reply(StopReason.END_TURN, ContentBlock.fromText("hello"))))
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("hello"))));
    }

    @Test
    void a_tool_use_is_a_request_for_actions_with_reasoning_beside_it() {
      ContentBlock reasoning =
          ContentBlock.fromReasoningContent(
              ReasoningContentBlock.fromReasoningText(
                  ReasoningTextBlock.builder().text("hmm").signature("sig").build()));
      ContentBlock use =
          ContentBlock.fromToolUse(
              ToolUseBlock.builder()
                  .toolUseId("call_1")
                  .name("depth")
                  .input(Document.fromMap(Map.of("lake", Document.fromString("ness"))))
                  .build());

      InferenceResult result =
          infer(reply(StopReason.TOOL_USE, reasoning, ContentBlock.fromText("looking"), use));

      assertThat(result).isInstanceOf(InferenceResult.Actions.class);
      List<Block.ActionRequestContent> blocks = ((InferenceResult.Actions) result).blocks();
      assertThat(blocks.get(0)).isInstanceOf(Block.Provider.class);
      assertThat(((Block.Provider) blocks.get(0)).vendor()).isEqualTo("aws.bedrock");
      assertThat(((Block.Provider) blocks.get(0)).payload()).contains("\"signature\":\"sig\"");
      assertThat(blocks.get(1)).isEqualTo(new Block.Commentary("looking"));
      assertThat(blocks.get(2))
          .isEqualTo(new Block.ToolCall("call_1", "depth", "{\"lake\":\"ness\"}"));
    }

    @Test
    void a_guardrail_and_a_content_filter_are_refusals_named_by_the_vendor() {
      assertThat(infer(reply(StopReason.GUARDRAIL_INTERVENED)))
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Refusal("guardrail_intervened"));
      assertThat(infer(reply(StopReason.CONTENT_FILTERED)))
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Refusal("content_filtered"));
    }

    @Test
    void an_empty_answer_is_a_fault_naming_the_stop_reason() {
      InferenceResult result = infer(reply(StopReason.MAX_TOKENS));

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure().reason()).contains("max_tokens");
    }
  }

  @Nested
  class WhatAFailureMeans {

    @Test
    void throttling_and_server_errors_are_transient() {
      assertThat(inferFailing(ThrottlingException.builder().message("slow down").build()))
          .isInstanceOf(Failure.Transient.class);
      assertThat(
              inferFailing(
                  AwsServiceException.builder().statusCode(502).message("bad gateway").build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void a_validation_error_is_permanent() {
      assertThat(inferFailing(ValidationException.builder().message("bad request").build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void a_client_side_failure_is_unknown() {
      assertThat(inferFailing(SdkClientException.create("connection reset")))
          .isInstanceOf(Failure.Unknown.class);
    }

    @Test
    void a_bug_in_the_adapter_is_not_dressed_up_as_the_model_failing() {
      IllegalStateException bug = new IllegalStateException("a bug in here");
      assertThatThrownBy(() -> inferFailing(bug)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  class Configuration {

    @Test
    void closing_the_provider_closes_the_client_it_was_given() {
      ScriptedClient client = new ScriptedClient(null, null);
      new BedrockInferenceProvider(client, MAPPER).close();
      assertThat(client.closed()).isTrue();
    }

    @Test
    void without_a_region_the_config_refuses_to_build() {
      assertThatThrownBy(() -> BedrockInferenceProvider.create(c -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("region");
    }

    @Test
    void the_name_is_the_vendors() {
      assertThat(new BedrockInferenceProvider(new ScriptedClient(null, null), MAPPER).name())
          .isEqualTo("Bedrock");
    }
  }

  @Nested
  class TheEdges {

    @Test
    void a_reply_with_no_output_at_all_is_an_empty_answer() {
      ConverseResponse bare = ConverseResponse.builder().stopReason(StopReason.END_TURN).build();

      InferenceResult result = infer(bare);

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
    }

    @Test
    void redacted_and_unsigned_reasoning_and_a_call_without_input_are_carried() {
      ContentBlock redacted =
          ContentBlock.fromReasoningContent(
              ReasoningContentBlock.fromRedactedContent(
                  software.amazon.awssdk.core.SdkBytes.fromByteArray(new byte[] {7})));
      ContentBlock unsigned =
          ContentBlock.fromReasoningContent(
              ReasoningContentBlock.fromReasoningText(
                  ReasoningTextBlock.builder().text("hmm").build()));
      ContentBlock use =
          ContentBlock.fromToolUse(ToolUseBlock.builder().toolUseId("c1").name("ping").build());

      InferenceResult result = infer(reply(StopReason.TOOL_USE, redacted, unsigned, use));

      List<Block.ActionRequestContent> blocks = ((InferenceResult.Actions) result).blocks();
      assertThat(((Block.Provider) blocks.get(0)).payload()).contains("\"type\":\"redacted\"");
      assertThat(((Block.Provider) blocks.get(1)).payload()).contains("\"signature\":\"\"");
      assertThat(blocks.get(2)).isEqualTo(new Block.ToolCall("c1", "ping", "{}"));
    }

    @Test
    void from_env_is_the_config_route() {
      org.junit.jupiter.api.Assumptions.assumeTrue(
          System.getenv("AWS_REGION") == null && System.getenv("AWS_DEFAULT_REGION") == null);
      assertThatThrownBy(BedrockInferenceProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  class BlankText {

    @Test
    void whitespace_is_not_content() {
      InferenceResult result =
          infer(
              reply(
                  StopReason.END_TURN, ContentBlock.fromText("   "), ContentBlock.fromText("hi")));

      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("hi"))));
    }

    @Test
    void a_reply_whose_output_has_no_message_is_empty() {
      ConverseResponse hollow =
          ConverseResponse.builder()
              .stopReason(StopReason.END_TURN)
              .output(ConverseOutput.builder().build())
              .build();

      assertThat(infer(hollow)).isInstanceOf(InferenceResult.Fault.class);
    }
  }
}
