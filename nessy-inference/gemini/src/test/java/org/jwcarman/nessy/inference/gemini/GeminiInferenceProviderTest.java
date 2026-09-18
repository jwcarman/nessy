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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
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
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The Gemini provider")
class GeminiInferenceProviderTest {

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
   * time, each other part whole, and the finish reason only on the last. A reply with no candidates
   * is one partial saying so.
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
      partials.add(GenerateContentResponse.builder().candidates(List.of(empty.build())).build());
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

      assertThat(infer(priced).usage()).isEqualTo(new Usage(5, 9));
      assertThat(infer(reply(new FinishReason("STOP"), Part.fromText("hello"))).usage())
          .isEqualTo(Usage.unknown());
    }
  }

  @Nested
  class WhatIsNarrated {

    private final List<AgentEvent> narrated = new ArrayList<>();

    @Test
    void thoughts_and_prose_are_narrated_as_they_arrive_and_the_reply_is_read_whole() {
      GenerateContentResponse response =
          reply(
              new FinishReason("STOP"),
              Part.builder().text("hmm, a lake").thought(true).build(),
              Part.fromText("a lake monster"));

      InferenceResult result =
          new GeminiInferenceProvider(new ScriptedClient(response, null), MAPPER)
              .infer(request(), narrated::add);

      assertThat(narrated)
          .containsExactly(
              new AgentEvent.ThinkingDelta("hmm, "),
              new AgentEvent.ThinkingDelta("a lak"),
              new AgentEvent.ThinkingDelta("e"),
              new AgentEvent.ContentDelta("a lak"),
              new AgentEvent.ContentDelta("e mon"),
              new AgentEvent.ContentDelta("ster"));
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
          new GeminiInferenceProvider(silent, MAPPER).infer(request(), narrated::add);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class));
      assertThat(narrated).isEmpty();
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
      assertThatThrownBy(() -> GeminiInferenceProvider.create(c -> {}))
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
