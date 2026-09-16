package org.jwcarman.nessy.inference.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
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

    @Override
    public ConverseResponse converse(ConverseRequest request) {
      if (failure != null) {
        throw failure;
      }
      return response;
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }

  @Nested
  class WhatComesBack {

    @Test
    void prose_alone_is_an_answer() {
      assertThat(infer(reply(StopReason.END_TURN, ContentBlock.fromText("hello"))))
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
          .isEqualTo(new InferenceResult.Refusal("guardrail_intervened"));
      assertThat(infer(reply(StopReason.CONTENT_FILTERED)))
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
}
