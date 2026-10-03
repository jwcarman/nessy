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
package org.jwcarman.nessy.inference.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.BlockedReason;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponsePromptFeedback;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingLevel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The Gemini provider")
class GeminiInferenceProviderTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static InferenceRequest request() {
    Turn open =
        new Turn(
            new TurnId(1), new Input(new Seq(1), List.of(new Block.Text("hi"))), List.of(), null);
    return new InferenceRequest(
        new SystemPrompt("be brief"),
        InferenceContext.of(List.of(open)),
        Toolset.none(),
        new InferenceOptions("gemini-3.6-flash", 256));
  }

  private static GenerateContentResponse reply(FinishReason finish, Part... parts) {
    return GenerateContentResponse.builder()
        .candidates(
            List.of(
                Candidate.builder()
                    .content(Content.builder().role("model").parts(List.of(parts)).build())
                    .finishReason(finish)
                    .build()))
        .build();
  }

  /**
   * The partials a server would have streamed this reply as: each text part five characters at a
   * time, each other part whole, and the finish reason and usage only on the last. A reply with no
   * candidates is one partial saying so; a reply whose parts hold no text at all is one partial
   * carrying the finish reason and usage.
   */
  static List<GenerateContentResponse> partialsOf(GenerateContentResponse response) {
    List<Candidate> candidates = response.candidates().orElse(List.of());
    if (candidates.isEmpty()) {
      return List.of(response);
    }
    Candidate candidate = candidates.getFirst();
    List<Part> pieces = new ArrayList<>();
    for (Part part : candidate.content().flatMap(Content::parts).orElse(List.of())) {
      if (part.text().isPresent() && part.thoughtSignature().isEmpty()) {
        String text = part.text().get();
        for (int i = 0; i < text.length(); i += 5) {
          Part.Builder piece =
              Part.builder().text(text.substring(i, Math.min(text.length(), i + 5)));
          if (part.thought().orElse(false)) {
            piece.thought(true);
          }
          pieces.add(piece.build());
        }
      } else {
        pieces.add(part);
      }
    }
    List<GenerateContentResponse> partials = new ArrayList<>();
    for (int i = 0; i < pieces.size(); i++) {
      Candidate.Builder partial =
          Candidate.builder()
              .content(Content.builder().role("model").parts(List.of(pieces.get(i))).build());
      GenerateContentResponse.Builder built = GenerateContentResponse.builder();
      if (i == pieces.size() - 1) {
        candidate.finishReason().ifPresent(partial::finishReason);
        response.usageMetadata().ifPresent(built::usageMetadata);
      }
      partials.add(built.candidates(List.of(partial.build())).build());
    }
    if (partials.isEmpty()) {
      Candidate.Builder empty = Candidate.builder();
      candidate.finishReason().ifPresent(empty::finishReason);
      GenerateContentResponse.Builder built = GenerateContentResponse.builder();
      response.usageMetadata().ifPresent(built::usageMetadata);
      partials.add(built.candidates(List.of(empty.build())).build());
    }
    return partials;
  }

  private static InferenceResult infer(GenerateContentResponse response) {
    GeminiClient client = new ScriptedClient(response, null);
    return new GeminiInferenceProvider(client, MAPPER).infer(request());
  }

  private static Failure inferFailing(RuntimeException failure) {
    GeminiClient client = new ScriptedClient(null, failure);
    InferenceResult result = new GeminiInferenceProvider(client, MAPPER).infer(request());
    assertThat(result).isInstanceOf(InferenceResult.Fault.class);
    return ((InferenceResult.Fault) result).failure();
  }

  /** A client with one reply, or one failure, and a record of whether it was closed. */
  private record ScriptedClient(
      GenerateContentResponse response, RuntimeException failure, AtomicBoolean closed)
      implements GeminiClient {

    ScriptedClient(GenerateContentResponse response, RuntimeException failure) {
      this(response, failure, new AtomicBoolean());
    }

    /** The reply cut into partials, the way the server streams it. */
    @Override
    public Stream<GenerateContentResponse> generateContentStream(
        String model, List<Content> contents, com.google.genai.types.GenerateContentConfig config) {
      if (failure != null) {
        throw failure;
      }
      return partialsOf(response).stream();
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }

  @Nested
  class WhatItCost {

    @Test
    void prompt_tokens_in_and_candidate_plus_thought_tokens_out() {
      GenerateContentResponse priced =
          reply(new FinishReason("STOP"), Part.fromText("hello")).toBuilder()
              .usageMetadata(
                  GenerateContentResponseUsageMetadata.builder()
                      .promptTokenCount(5)
                      .candidatesTokenCount(7)
                      .thoughtsTokenCount(2)
                      .build())
              .build();

      // Thinking is billed at the output rate, so it is summed into the output AND kept on its own.
      // The model falls back to what was asked for, because this reply carries no modelVersion.
      assertThat(infer(priced).usage())
          .isEqualTo(new Usage("gemini-3.6-flash", 5, 9, null, null, 2));
      // No usageMetadata at all: nothing counted, but we still know what was asked.
      assertThat(infer(reply(new FinishReason("STOP"), Part.fromText("hello"))).usage())
          .isEqualTo(Usage.unreported("gemini-3.6-flash"));
    }
  }

  @Nested
  class WhatIsNarrated {

    private final Narration narrated = new Narration();

    @Test
    void thoughts_and_prose_are_narrated_as_they_arrive_and_the_reply_is_read_whole() {
      GenerateContentResponse response =
          reply(
              new FinishReason("STOP"),
              Part.builder().text("hmm, a lake").thought(true).build(),
              Part.fromText("a lake monster"));

      InferenceResult result =
          new GeminiInferenceProvider(new ScriptedClient(response, null), MAPPER)
              .infer(request(), narrated);

      assertThat(narrated.fragments())
          .containsExactly(
              Narration.Fragment.thinking("hmm, "),
              Narration.Fragment.thinking("a lak"),
              Narration.Fragment.thinking("e"),
              Narration.Fragment.text("a lak"),
              Narration.Fragment.text("e mon"),
              Narration.Fragment.text("ster"));
      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("a lake monster"))));
    }

    @Test
    void a_stream_of_nothing_is_a_fault() {
      GeminiClient silent =
          new GeminiClient() {
            @Override
            public Stream<GenerateContentResponse> generateContentStream(
                String model,
                List<Content> contents,
                com.google.genai.types.GenerateContentConfig config) {
              return Stream.of();
            }

            @Override
            public void close() {
              // Nothing to close.
            }
          };

      InferenceResult result =
          new GeminiInferenceProvider(silent, MAPPER).infer(request(), narrated);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class));
      assertThat(narrated.fragments()).isEmpty();
    }

    /**
     * The finish reason comes on the last partial, so a stream without one stopped partway. What
     * arrived is half a reply, never the answer; nobody knows whether the model finished, so the
     * retry policy decides.
     */
    @Test
    void a_stream_cut_short_is_an_unknown_fault_rather_than_half_an_answer() {
      List<GenerateContentResponse> partials =
          partialsOf(reply(new FinishReason("STOP"), Part.fromText("a lake monster")));
      List<GenerateContentResponse> cut = partials.subList(0, partials.size() - 1);
      GeminiClient early =
          new GeminiClient() {
            @Override
            public Stream<GenerateContentResponse> generateContentStream(
                String model,
                List<Content> contents,
                com.google.genai.types.GenerateContentConfig config) {
              return cut.stream();
            }

            @Override
            public void close() {
              // Nothing to close.
            }
          };

      InferenceResult result = new GeminiInferenceProvider(early, MAPPER).infer(request());

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Unknown.class);
                assertThat(fault.failure().reason()).contains("ended before");
              });
    }
  }

  @Nested
  class ItsVendorProperties {

    private static final String BUDGET = "gemini.generationConfig.thinkingConfig.thinkingBudget";

    private final List<com.google.genai.types.GenerateContentConfig> sent = new ArrayList<>();

    private GeminiClient recording() {
      return new GeminiClient() {
        @Override
        public Stream<GenerateContentResponse> generateContentStream(
            String model,
            List<Content> contents,
            com.google.genai.types.GenerateContentConfig config) {
          sent.add(config);
          return partialsOf(reply(new FinishReason("STOP"), Part.fromText("ok"))).stream();
        }

        @Override
        public void close() {
          // Nothing to close.
        }
      };
    }

    private static InferenceRequest carrying(Map<String, String> agentType) {
      InferenceRequest base = request();
      return new InferenceRequest(
          base.systemPrompt(),
          base.context(),
          base.toolset(),
          new InferenceOptions("gemini-3.6-flash", 256, agentType));
    }

    @Test
    void a_property_on_the_provider_reaches_every_request() {
      GeminiInferenceProvider provider =
          new GeminiInferenceProvider(recording(), MAPPER, Map.of(BUDGET, "1024"));

      provider.infer(carrying(Map.of()));

      assertThat(sent)
          .singleElement()
          .satisfies(
              config ->
                  assertThat(config.thinkingConfig().orElseThrow().thinkingBudget())
                      .contains(1024));
    }

    @Test
    void an_enum_set_in_code_reaches_the_sdk_as_lowercase_text() {
      Map<String, String> typed =
          Map.of(
              GeminiProperties.THINKING_LEVEL.name(),
              GeminiProperties.THINKING_LEVEL.format(GeminiThinkingLevel.HIGH));
      GeminiInferenceProvider provider = new GeminiInferenceProvider(recording(), MAPPER, typed);

      provider.infer(carrying(Map.of()));

      assertThat(sent)
          .singleElement()
          .satisfies(
              config ->
                  assertThat(config.thinkingConfig().orElseThrow().thinkingLevel().orElseThrow())
                      .hasToString("high"));
    }

    @Test
    void typed_properties_set_in_code_reach_the_request_as_the_sdk_values() {
      Map<String, String> typed =
          Map.of(
              GeminiProperties.THINKING_LEVEL.name(),
              GeminiProperties.THINKING_LEVEL.format(GeminiThinkingLevel.HIGH),
              GeminiProperties.INCLUDE_THOUGHTS.name(),
              GeminiProperties.INCLUDE_THOUGHTS.format(true));
      GeminiInferenceProvider provider = new GeminiInferenceProvider(recording(), MAPPER, typed);

      provider.infer(carrying(Map.of()));

      assertThat(sent)
          .singleElement()
          .satisfies(
              config -> {
                assertThat(
                        config
                            .thinkingConfig()
                            .orElseThrow()
                            .thinkingLevel()
                            .orElseThrow()
                            .knownEnum())
                    .isEqualTo(ThinkingLevel.Known.HIGH);
                assertThat(config.thinkingConfig().orElseThrow().includeThoughts()).contains(true);
              });
    }

    @Test
    void validate_warns_once_for_an_unsupported_agent_type_property_and_inference_stays_silent() {
      GeminiInferenceProvider provider = new GeminiInferenceProvider(recording(), MAPPER);
      InferenceRequest request = carrying(Map.of("gemini.generationConfig.maxOutputTokens", "9"));

      List<ILoggingEvent> atValidate =
          LogCapture.during(GeminiPropertyReader.class, () -> provider.validate(request.options()));
      List<ILoggingEvent> atInference =
          LogCapture.during(
              GeminiPropertyReader.class,
              () -> {
                provider.infer(request);
                provider.infer(request);
              });

      assertThat(LogCapture.warnings(atValidate))
          .singleElement()
          .asString()
          .contains("'gemini.generationConfig.maxOutputTokens'")
          .contains(BUDGET);
      assertThat(atInference).isEmpty();
      assertThat(sent)
          .hasSize(2)
          .allSatisfy(
              config -> {
                assertThat(config.httpOptions()).isEmpty();
                assertThat(config.maxOutputTokens()).contains(256);
              });
    }
  }

  @Nested
  class WhatComesBack {

    @Test
    void prose_alone_is_an_answer() {
      InferenceResult result = infer(reply(new FinishReason("STOP"), Part.fromText("hello")));

      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("hello"))));
    }

    @Test
    void a_function_call_is_a_request_for_actions_with_its_signature_beside_it() {
      Part call =
          Part.builder()
              .functionCall(
                  FunctionCall.builder()
                      .id("call_1")
                      .name("depth")
                      .args(Map.of("lake", "ness"))
                      .build())
              .thoughtSignature("sig".getBytes(StandardCharsets.UTF_8))
              .build();

      InferenceResult result =
          infer(reply(new FinishReason("STOP"), Part.fromText("looking"), call));

      assertThat(result).isInstanceOf(InferenceResult.Actions.class);
      List<Block.ActionRequestContent> blocks = ((InferenceResult.Actions) result).blocks();
      assertThat(blocks.get(0)).isEqualTo(new Block.Commentary("looking"));
      assertThat(blocks.get(1))
          .isEqualTo(new Block.ToolCall("call_1", "depth", "{\"lake\":\"ness\"}"));
      assertThat(blocks.get(2)).isInstanceOf(Block.Provider.class);
      assertThat(((Block.Provider) blocks.get(2)).vendor()).isEqualTo("gcp.gemini");
      assertThat(((Block.Provider) blocks.get(2)).payload()).contains("call_1").contains("c2ln");
    }

    @Test
    void a_call_without_an_id_is_given_one() {
      Part call =
          Part.builder()
              .functionCall(FunctionCall.builder().name("depth").args(Map.of()).build())
              .build();

      InferenceResult result = infer(reply(new FinishReason("STOP"), call));

      Block.ToolCall made = (Block.ToolCall) ((InferenceResult.Actions) result).blocks().getFirst();
      assertThat(made.id().value()).startsWith("gemini-call-");
    }

    @Test
    void a_thought_part_is_not_content() {
      Part thought = Part.builder().text("hmm").thought(true).build();

      InferenceResult result = infer(reply(new FinishReason("STOP"), thought, Part.fromText("hi")));

      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("hi"))));
    }

    @Test
    void a_safety_stop_is_a_refusal_named_by_the_vendor() {
      InferenceResult result = infer(reply(new FinishReason("SAFETY")));

      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Refusal("SAFETY"));
    }

    @Test
    void a_blocked_prompt_is_a_refusal_too() {
      GenerateContentResponse blocked =
          GenerateContentResponse.builder()
              .promptFeedback(
                  GenerateContentResponsePromptFeedback.builder()
                      .blockReason(new BlockedReason("PROHIBITED_CONTENT"))
                      .build())
              .build();

      assertThat(infer(blocked))
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Refusal("PROHIBITED_CONTENT"));
    }

    @Test
    void an_empty_answer_is_a_fault_naming_the_finish_reason() {
      InferenceResult result = infer(reply(new FinishReason("MAX_TOKENS")));

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure())
          .isInstanceOf(Failure.Permanent.class)
          .extracting(Failure::reason)
          .asString()
          .contains("MAX_TOKENS");
    }
  }

  @Nested
  class WhenTheReplyWasCutOffAtTheOutputLimit {

    @Test
    void prose_cut_off_is_a_truncated_reply_not_an_answer() {
      InferenceResult result =
          infer(reply(new FinishReason("MAX_TOKENS"), Part.fromText("the lake is deep and")));

      assertThat(result).isInstanceOf(InferenceResult.Truncated.class);
      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(
              new InferenceResult.Truncated(List.of(new Block.Text("the lake is deep and"))));
    }

    @Test
    void a_function_call_cut_off_is_a_fault_not_actions() {
      Part call =
          Part.builder()
              .functionCall(
                  FunctionCall.builder().id("call_1").name("depth").args(Map.of()).build())
              .build();

      GenerateContentResponse cut =
          reply(new FinishReason("MAX_TOKENS"), call).toBuilder()
              .usageMetadata(
                  GenerateContentResponseUsageMetadata.builder()
                      .promptTokenCount(5)
                      .candidatesTokenCount(7)
                      .thoughtsTokenCount(2)
                      .build())
              .build();

      InferenceResult result = infer(cut);

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure())
          .isInstanceOf(Failure.Permanent.class)
          .extracting(Failure::reason)
          .asString()
          .contains("cut off")
          .contains("tool call")
          .contains("finish_reason=MAX_TOKENS");
      assertThat(result.usage()).isEqualTo(new Usage("gemini-3.6-flash", 5, 9, null, null, 2));
    }

    @Test
    void prose_before_a_cut_off_function_call_does_not_make_it_truncated() {
      Part call =
          Part.builder()
              .functionCall(
                  FunctionCall.builder().id("call_1").name("depth").args(Map.of()).build())
              .build();

      InferenceResult result =
          infer(reply(new FinishReason("MAX_TOKENS"), Part.fromText("looking"), call));

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure())
          .isInstanceOf(Failure.Permanent.class)
          .extracting(Failure::reason)
          .asString()
          .contains("cut off")
          .contains("tool call");
    }

    /**
     * What the wire sent on 2026-10-02 for a function call cut off at the output limit: this finish
     * reason, one empty text part, and no function-call part.
     */
    @Test
    void a_function_call_the_vendor_reports_as_malformed_is_a_fault_that_says_so() {
      GenerateContentResponse malformed =
          reply(new FinishReason("MALFORMED_FUNCTION_CALL"), Part.fromText("")).toBuilder()
              .usageMetadata(
                  GenerateContentResponseUsageMetadata.builder()
                      .promptTokenCount(5)
                      .candidatesTokenCount(7)
                      .thoughtsTokenCount(2)
                      .build())
              .build();

      InferenceResult result = infer(malformed);

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure())
          .isInstanceOf(Failure.Permanent.class)
          .extracting(Failure::reason)
          .asString()
          .contains("tool call was malformed")
          .contains("cut off at the output limit")
          .contains("finish_reason=MALFORMED_FUNCTION_CALL");
      assertThat(result.usage()).isEqualTo(new Usage("gemini-3.6-flash", 5, 9, null, null, 2));
    }

    @Test
    void a_malformed_function_call_is_not_run_though_a_call_came_with_it() {
      Part call =
          Part.builder()
              .functionCall(
                  FunctionCall.builder().id("call_1").name("depth").args(Map.of()).build())
              .build();

      InferenceResult result = infer(reply(new FinishReason("MALFORMED_FUNCTION_CALL"), call));

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure())
          .isInstanceOf(Failure.Permanent.class)
          .extracting(Failure::reason)
          .asString()
          .contains("tool call was malformed");
    }

    @Test
    void a_malformed_function_call_is_not_an_answer_though_text_came_with_it() {
      InferenceResult result =
          infer(reply(new FinishReason("MALFORMED_FUNCTION_CALL"), Part.fromText("looking it up")));

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure())
          .isInstanceOf(Failure.Permanent.class)
          .extracting(Failure::reason)
          .asString()
          .contains("tool call was malformed");
    }

    @Test
    void reasoning_alone_cut_off_is_the_empty_answer_fault() {
      Part thought = Part.builder().text("hmm, a lake").thought(true).build();

      InferenceResult result = infer(reply(new FinishReason("MAX_TOKENS"), thought));

      assertThat(result).isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure())
          .isInstanceOf(Failure.Permanent.class)
          .extracting(Failure::reason)
          .asString()
          .contains("empty answer")
          .contains("MAX_TOKENS");
    }
  }

  @Nested
  class WhatAFailureMeans {

    @Test
    void a_server_error_and_a_rate_limit_are_transient() {
      assertThat(inferFailing(new ServerException(503, "UNAVAILABLE", "try later")))
          .isInstanceOf(Failure.Transient.class);
      assertThat(inferFailing(new ClientException(429, "RESOURCE_EXHAUSTED", "slow down")))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void any_other_client_error_is_permanent() {
      assertThat(inferFailing(new ClientException(400, "INVALID_ARGUMENT", "bad request")))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void a_transport_failure_is_unknown() {
      assertThat(inferFailing(new GenAiIOException("connection reset")))
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
      new GeminiInferenceProvider(client, MAPPER).close();
      assertThat(client.closed()).isTrue();
    }

    @Test
    void a_client_the_application_handed_in_is_not_closed_by_the_provider() {
      // The real SDK client is final and cannot be observed; GeminiClient.over is the seam, and
      // owned=false is the branch a handed-in client takes.
      GeminiClient wrapped =
          GeminiClient.over(com.google.genai.Client.builder().apiKey("k").build(), false);
      org.assertj.core.api.Assertions.assertThatCode(wrapped::close).doesNotThrowAnyException();
    }

    @Test
    void without_a_key_the_config_refuses_to_build() {
      assertThatThrownBy(() -> GeminiInferenceProvider.of(c -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey");
    }

    @Test
    void the_name_is_the_vendors() {
      assertThat(new GeminiInferenceProvider(new ScriptedClient(null, null), MAPPER).name())
          .isEqualTo("Gemini");
    }
  }
}
