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
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.completed;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.fields;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.functionCall;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.message;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.reasoning;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.refusal;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.replying;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.response;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.usage;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.webSearchCall;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class OpenAiResponsesInferenceProviderTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  static final InferenceRequest REQUEST =
      new InferenceRequest(
          new SystemPrompt("you are a helpful assistant"),
          InferenceContext.of(
              List.of(
                  new Turn(
                      new TurnId(1),
                      new Input(new Seq(1), List.of(new Block.Text("hello"))),
                      List.of(),
                      null,
                      0))),
          Toolset.none(),
          InferenceOptions.of("gpt-4o"));

  static OpenAiResponsesInferenceProvider provider(OpenAIClient client) {
    return new OpenAiResponsesProviderConfig().client(client).build();
  }

  private static InferenceResult inferReplying(Map<String, Object> response) {
    return provider(replying(response)).infer(REQUEST);
  }

  private static Failure inferFailing(RuntimeException failure) {
    InferenceResult result =
        provider(
                ResponseStreams.client(
                    params -> {
                      throw failure;
                    }))
            .infer(REQUEST);
    assertThat(result).isInstanceOf(InferenceResult.Fault.class);
    return ((InferenceResult.Fault) result).failure();
  }

  private static Headers emptyHeaders() {
    return Headers.builder().build();
  }

  private static Map<String, Object> parse(String json) {
    return MAPPER.readValue(json, new TypeReference<>() {});
  }

  @Nested
  class WhatItCost {

    @Test
    void the_usage_on_the_completed_event_is_the_results() {
      InferenceResult result =
          inferReplying(
              response("completed", List.of(message("msg_1", "a lake monster")), usage(3, 5)));

      assertThat(result.usage()).isEqualTo(Usage.of("gpt-4o", 3, 5));
      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
    }

    @Test
    void a_server_that_does_not_count_leaves_the_cost_unknown() {
      assertThat(inferReplying(completed(List.of(message("msg_1", "ok")))).usage())
          .isEqualTo(Usage.unreported("gpt-4o"));
    }

    @Test
    void the_detail_counts_are_the_cache_and_reasoning_breakdown() {
      Map<String, Object> counted = usage(30, 50);
      counted.put("input_tokens_details", fields("cached_tokens", 10));
      counted.put("output_tokens_details", fields("reasoning_tokens", 20));

      InferenceResult result =
          inferReplying(response("completed", List.of(message("msg_1", "ok")), counted));

      assertThat(result.usage()).isEqualTo(new Usage("gpt-4o", 30, 50, 10, null, 20));
    }

    /** §5i: the plain accessors would throw here; asKnown() reads "not said" as null. */
    @Test
    void a_server_that_omits_the_detail_objects_reports_input_and_output_and_no_cache_counts() {
      InferenceResult result =
          inferReplying(response("completed", List.of(message("msg_1", "ok")), usage(3, 5)));

      assertThat(result.usage().cacheReadTokens().counted()).isFalse();
      assertThat(result.usage().reasoningTokens().counted()).isFalse();
      assertThat(result.usage()).isEqualTo(Usage.of("gpt-4o", 3, 5));
    }

    /** Review Focus 4. */
    @Test
    void a_server_that_omits_the_input_count_still_answers() {
      InferenceResult result =
          inferReplying(
              response("completed", List.of(message("msg_1", "ok")), fields("output_tokens", 5)));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(result.usage()).isEqualTo(new Usage("gpt-4o", null, 5, null, null, null));
    }
  }

  @Nested
  class WhatComesBack {

    /**
     * A reasoning model that spent its whole budget thinking says so, as finish_reason=length does
     * on the chat wire.
     */
    @Test
    void an_empty_answer_is_a_fault_that_names_the_status_and_the_reason() {
      Map<String, Object> spent = response("incomplete", List.of(), null);
      spent.put("incomplete_details", fields("reason", "max_output_tokens"));

      InferenceResult result = inferReplying(spent);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class);
                assertThat(fault.failure().reason())
                    .contains("empty")
                    .contains("incomplete")
                    .contains("max_output_tokens");
              });
    }

    @Test
    void plain_prose_is_an_answer() {
      assertThat(inferReplying(completed(List.of(message("msg_1", "1412 metres")))))
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("1412 metres"))));
    }

    @Test
    void calls_are_a_request_for_actions_carrying_the_arguments_the_model_wrote() {
      InferenceResult result =
          inferReplying(
              completed(List.of(functionCall("call_1", "lookup", "{\"q\":\"loch ness\"}"))));

      assertThat(result)
          .isEqualTo(
              new InferenceResult.Actions(
                      List.of(
                          new Block.ToolCall(
                              new CallId("call_1"),
                              new ToolName("lookup"),
                              "{\"q\":\"loch ness\"}")))
                  .withUsage(Usage.unreported("gpt-4o")));
    }

    @Test
    void prose_beside_calls_is_kept_as_commentary() {
      InferenceResult result =
          inferReplying(
              completed(
                  List.of(
                      message("msg_1", "Let me look."), functionCall("call_1", "lookup", "{}"))));

      assertThat(((InferenceResult.Actions) result).blocks().getFirst())
          .isEqualTo(new Block.Commentary("Let me look."));
    }

    @Test
    void whitespace_beside_calls_is_not_kept_as_commentary() {
      InferenceResult result =
          inferReplying(
              completed(List.of(message("msg_1", "\n\n"), functionCall("call_1", "lookup", "{}"))));

      assertThat(((InferenceResult.Actions) result).blocks())
          .isNotEmpty()
          .noneMatch(Block.Commentary.class::isInstance);
    }

    @Test
    void a_refusal_is_a_refusal_rather_than_an_answer_that_happens_to_say_no() {
      assertThat(inferReplying(completed(List.of(refusal("I cannot help with that")))))
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Refusal("I cannot help with that"));
    }

    @Test
    void a_failed_response_for_a_rate_limit_is_worth_another_attempt() {
      Map<String, Object> failed = response("failed", List.of(), null);
      failed.put("error", fields("code", "rate_limit_exceeded", "message", "slow down"));

      InferenceResult result = inferReplying(failed);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Transient.class);
                assertThat(fault.failure().reason()).contains("slow down");
              });
    }

    @Test
    void a_failed_response_for_a_bad_prompt_is_permanent() {
      Map<String, Object> failed = response("failed", List.of(), null);
      failed.put("error", fields("code", "invalid_prompt", "message", "no"));

      assertThat(((InferenceResult.Fault) inferReplying(failed)).failure())
          .isInstanceOf(Failure.Permanent.class);
    }

    /**
     * §8: nothing here offered a hosted tool, so its output is dropped, as the chat wire drops
     * custom calls.
     */
    @Test
    void a_hosted_tool_item_is_dropped_leaving_its_siblings_in_order() {
      InferenceResult result =
          inferReplying(
              completed(
                  List.of(
                      message("msg_1", "Looking."),
                      webSearchCall(),
                      functionCall("call_1", "lookup", "{}"))));

      assertThat(((InferenceResult.Actions) result).blocks())
          .containsExactly(
              new Block.Commentary("Looking."),
              new Block.ToolCall(new CallId("call_1"), new ToolName("lookup"), "{}"));
    }

    @Test
    void a_stream_with_no_terminal_event_is_a_fault_that_says_it_ended_early() {
      InferenceResult result = provider(ResponseStreams.client(params -> List.of())).infer(REQUEST);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class);
                assertThat(fault.failure().reason()).contains("ended before");
              });
    }

    @Test
    void the_model_asked_for_is_the_one_in_the_options() {
      ResponseCreateParams[] captured = new ResponseCreateParams[1];
      provider(
              ResponseStreams.client(
                  params -> {
                    captured[0] = params;
                    return ResponseStreams.eventsOf(completed(List.of(message("msg_1", "ok"))));
                  }))
          .infer(REQUEST);

      assertThat(captured[0].model().orElseThrow().asString()).isEqualTo("gpt-4o");
    }
  }

  @Nested
  class ReasoningItems {

    @Test
    void one_beside_calls_is_kept_as_a_provider_block_in_its_arrival_position() {
      InferenceResult result =
          inferReplying(
              completed(
                  List.of(
                      reasoning("rs_1", "AAAA", "weighing it"),
                      functionCall("call_1", "lookup", "{}"))));

      List<Block.ActionRequestContent> blocks = ((InferenceResult.Actions) result).blocks();
      assertThat(blocks).hasSize(2);
      assertThat(blocks.get(0))
          .isInstanceOfSatisfying(
              Block.Provider.class,
              provider -> {
                assertThat(provider.vendor()).isEqualTo("openai");
                assertThat(parse(provider.payload()))
                    .isEqualTo(
                        parse(
                            "{\"id\":\"rs_1\",\"encrypted_content\":\"AAAA\",\"summary\":"
                                + "[{\"type\":\"summary_text\",\"text\":\"weighing it\"}]}"));
              });
      assertThat(blocks.get(1)).isInstanceOf(Block.ToolCall.class);
    }

    @Test
    void one_beside_a_final_answer_is_stored_beside_the_answer_s_text() {
      InferenceResult result =
          inferReplying(
              completed(
                  List.of(reasoning("rs_1", "AAAA", "weighing it"), message("msg_1", "Paris"))));

      List<Block.AnswerContent> blocks = ((InferenceResult.Answer) result).blocks();
      assertThat(blocks).hasSize(2);
      assertThat(blocks.get(0)).isInstanceOf(Block.Provider.class);
      assertThat(blocks.get(1)).isEqualTo(new Block.Text("Paris"));
    }

    /** Review Focus 5: nothing encrypted is nothing to hand back. */
    @Test
    void a_reasoning_item_with_nothing_encrypted_is_not_kept() {
      InferenceResult result =
          inferReplying(
              completed(List.of(reasoning("rs_1", null, "hmm"), message("msg_1", "Paris"))));

      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("Paris"))));
    }

    /**
     * What the provider stores is exactly what the projection reads back for the in-flight turn.
     */
    @Test
    void the_stored_item_is_the_one_the_next_request_replays() {
      InferenceResult result =
          inferReplying(
              completed(
                  List.of(
                      reasoning("rs_1", "AAAA", "weighing it"),
                      functionCall("call_1", "lookup", "{}"))));
      List<Block.ActionRequestContent> stored = ((InferenceResult.Actions) result).blocks();
      Exchange exchange =
          new Exchange(
              new Seq(2),
              stored,
              List.of(
                  new ToolOutcome.Succeeded(new CallId("call_1"), List.of(new Block.Text("ok")))));
      Turn inFlight =
          new Turn(
              new TurnId(1),
              new Input(new Seq(1), List.of(new Block.Text("hello"))),
              List.of(exchange),
              null,
              0);
      InferenceRequest next =
          new InferenceRequest(
              new SystemPrompt("you are a helpful assistant"),
              InferenceContext.of(List.of(inFlight)),
              Toolset.none(),
              InferenceOptions.of("gpt-4o"));

      List<ResponseInputItem> items =
          OpenAiResponsesRequests.toParams(next, "openai", MAPPER)
              .input()
              .orElseThrow()
              .asResponse();

      ResponseReasoningItem replayed =
          items.stream()
              .filter(ResponseInputItem::isReasoning)
              .findFirst()
              .orElseThrow()
              .asReasoning();
      assertThat(replayed.id()).isEqualTo("rs_1");
      assertThat(replayed.encryptedContent()).contains("AAAA");
      assertThat(replayed.summary())
          .extracting(ResponseReasoningItem.Summary::text)
          .containsExactly("weighing it");
    }

    @Test
    void a_provider_answering_for_another_vendor_tags_its_items_with_that_vendor() {
      InferenceResult result =
          new OpenAiResponsesProviderConfig()
              .client(
                  replying(
                      completed(
                          List.of(
                              reasoning("rs_1", "AAAA", "hm"),
                              functionCall("call_1", "lookup", "{}")))))
              .vendor("x_ai")
              .build()
              .infer(REQUEST);

      assertThat(((InferenceResult.Actions) result).blocks().getFirst())
          .isInstanceOfSatisfying(
              Block.Provider.class, provider -> assertThat(provider.vendor()).isEqualTo("x_ai"));
    }
  }

  @Nested
  class Identity {

    @Test
    void reports_openai_as_its_name_and_its_configured_vendor() {
      OpenAiResponsesInferenceProvider provider =
          new OpenAiResponsesProviderConfig().apiKey("sk-test").vendor("perplexity").build();

      assertThat(provider.name()).isEqualTo("OpenAI");
      assertThat(provider.vendor()).isEqualTo("perplexity");
      provider.close();
    }

    @Test
    void the_default_vendor_is_openai() {
      OpenAiResponsesInferenceProvider provider =
          OpenAiResponsesInferenceProvider.of(c -> c.apiKey("sk-test"));

      assertThat(provider.vendor()).isEqualTo("openai");
      provider.close();
    }
  }

  /**
   * The chat adapter's classification, shared through OpenAiFailures, reached through this
   * adapter's infer.
   */
  @Nested
  class WhatAFailureMeans {

    @Test
    void a_rate_limit_is_worth_another_attempt() {
      assertThat(inferFailing(RateLimitException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void so_is_an_internal_server_error() {
      assertThat(
              inferFailing(
                  InternalServerException.builder()
                      .statusCode(500)
                      .headers(emptyHeaders())
                      .build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void and_a_gateway_error_reported_as_a_5xx() {
      assertThat(
              inferFailing(
                  InternalServerException.builder()
                      .statusCode(503)
                      .headers(emptyHeaders())
                      .build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void and_the_sdk_s_own_transient_marker() {
      assertThat(inferFailing(new OpenAIRetryableException("transient failure")))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void a_transport_failure_is_unknown_rather_than_transient() {
      assertThat(inferFailing(new OpenAIIoException("connection reset")))
          .isInstanceOf(Failure.Unknown.class);
    }

    @Test
    void a_bad_request_is_permanent() {
      assertThat(inferFailing(BadRequestException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void so_is_an_unauthorized_call() {
      assertThat(inferFailing(UnauthorizedException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_permission_denied() {
      assertThat(inferFailing(PermissionDeniedException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_not_found() {
      assertThat(inferFailing(NotFoundException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_an_unprocessable_entity() {
      assertThat(
              inferFailing(UnprocessableEntityException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_status_code_the_sdk_does_not_recognise() {
      assertThat(
              inferFailing(
                  UnexpectedStatusCodeException.builder()
                      .statusCode(409)
                      .headers(emptyHeaders())
                      .build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_response_that_would_not_decode() {
      assertThat(inferFailing(new OpenAIInvalidDataException("unrecognized enum value")))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_never_a_rejection_of_the_content_itself() {
      assertThat(inferFailing(BadRequestException.builder().headers(emptyHeaders()).build()))
          .isNotInstanceOf(Failure.Rejected.class);
    }

    @Test
    void a_bug_in_the_adapter_is_not_dressed_up_as_the_model_failing() {
      IllegalStateException bug = new IllegalStateException("a bug in here");
      assertThatThrownBy(() -> inferFailing(bug))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("a bug in here");
    }
  }
}
