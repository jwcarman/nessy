package org.jwcarman.nessy.engine.inference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.net.ConnectException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import org.jwcarman.nessy.spi.narration.AgentNarrator;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * An {@link InferenceProvider} over an OpenAI-compatible endpoint, which is what LM Studio serves
 * locally.
 *
 * <p>Messages are projected onto the provider's wire here and nowhere else: the fold, the history
 * and the effect all speak in this project's own vocabulary, and only this class knows what a
 * "role" is called on the other side.
 *
 * <p>Holds no model name. Which model to call travels in {@link InferenceOptions}, so one
 * connection serves several agent types asking for different models instead of one adapter instance
 * per model.
 */
public class LmStudioInferenceProvider implements InferenceProvider {

  /**
   * Body fragments that mean "this content is unusable", each measured against a live server rather
   * than guessed. The empty-observation case renders to a prompt with no user turn at all, which
   * this provider reports through its template rather than as a validation error.
   *
   * <p>Deliberately not matching the context-length rejection, which is also a 400: the offending
   * content there is the accumulation rather than any one message, and treating it as rejected
   * would quarantine a perfectly good question.
   */
  private static final List<String> UNUSABLE_INPUT = List.of("No user query found in messages");

  private final RestClient restClient;

  public LmStudioInferenceProvider(RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * Total for anything the provider can do to us. A classified failure comes back as {@link
   * InferenceResult.Fault} rather than thrown, because the agent that asked for this is mid-turn
   * and only something reaching the fold ends that turn.
   *
   * <p>The catch stays narrow on purpose. A {@link RestClientException} is the provider failing,
   * and this class is the only thing that knows what its status codes mean. Anything else -- a null
   * dereference here, a decode that blows up -- is a bug in this adapter, and a bug dressed as
   * {@code Failure.Unknown} would be retried three times and then recorded as the model's fault.
   */
  /**
   * Does not stream, so it narrates nothing and answers all at once.
   *
   * <p>Which is the point of the narrator being a parameter rather than a second interface: this
   * adapter is unchanged by streaming existing, and a caller cannot tell it apart from one that
   * does stream.
   */
  @Override
  public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
    ChatRequest body = ChatRequest.of(request);
    try {
      ChatResponse response =
          restClient
              .post()
              .uri("/v1/chat/completions")
              .body(body)
              .retrieve()
              .body(ChatResponse.class);
      if (response == null || response.choices() == null || response.choices().isEmpty()) {
        // A 200 that carries no answer. Asking again returns the same nothing.
        return new InferenceResult.Fault(new Failure.Permanent("model returned no choices"));
      }
      return read(response.choices().getFirst().message());
    } catch (RestClientException e) {
      return new InferenceResult.Fault(classify(e));
    }
  }

  /**
   * Which of the two shapes an assistant message is.
   *
   * <p>Decided on the presence of {@code tool_calls} rather than on {@code finish_reason}, because
   * the content is the thing that has to be answered and several OpenAI-compatible servers report
   * the reason inconsistently while all of them put the calls in the same place.
   *
   * <p>The prose beside the calls is kept. Models routinely narrate what they are about to do, and
   * dropping it would re-send a conversation in which the assistant made calls with nothing said
   * about why.
   */
  private static InferenceResult read(WireMessage message) {
    List<WireToolCall> calls = message.toolCalls();
    if (calls == null || calls.isEmpty()) {
      return new InferenceResult.Answer(List.of(new Block.Text(message.content())));
    }
    // Commentary, not text, and the grammar is what says so: a turn that is still asking has
    // not answered, so prose arriving beside calls is the model talking while it works. This
    // is the one place that classification happens -- the adapter, where the shape of the
    // response says whether the model was working or answering.
    // Blank rather than empty, and only here. This provider sends "\n\n" as the content of a
    // message whose whole point is its calls -- formatting, not the model talking -- and a
    // commentary block made of it is a dim empty line in every console and a wasted block in
    // every later request. An ANSWER's leading newlines are kept, because there the whitespace
    // is part of what was said; this judgement is about this wire and stays in this adapter.
    return new InferenceResult.Actions(
        Stream.concat(
                message.content() == null || message.content().isBlank()
                    ? Stream.<Block.ActionRequestContent>of()
                    : Stream.<Block.ActionRequestContent>of(
                        new Block.Commentary(message.content())),
                calls.stream().map(WireToolCall::toBlock))
            .toList());
  }

  /**
   * Whether a rejection was about the content we sent, as opposed to how much of it.
   *
   * <p>Matching on the body text, because this provider gives no code to switch on -- measured, not
   * assumed. It is deliberately narrow: an unrecognised 400 stays {@link Failure.Permanent},
   * because this is the one classification that authorises throwing away something a person said,
   * and a wrong guess there is silent data loss. The context-length rejection is a 400 too and must
   * NOT match, since the offending content there is the accumulation rather than any one message.
   */
  private static Optional<String> rejection(RestClientResponseException response) {
    String body = response.getResponseBodyAsString();
    return UNUSABLE_INPUT.stream()
        .filter(body::contains)
        .findFirst()
        .map(marker -> "the model rejected this input: " + marker);
  }

  /**
   * Decides what a failed call means, which is the one thing this class knows and nothing above it
   * does.
   *
   * <p>The distinction that matters most is the last one. A request that never left, or was refused
   * before doing anything, definitely did not happen. A request that was accepted and then timed
   * out may well have been processed -- so it is not a failure at all, only an unanswered question,
   * and repeating it is safe exactly when repeating the work is safe.
   */
  private static Failure classify(RestClientException e) {
    if (e instanceof RestClientResponseException response) {
      HttpStatusCode status = response.getStatusCode();
      if (status.value() == 429 || status.is5xxServerError()) {
        return new Failure.Transient("model call failed: " + status);
      }
      return rejection(response)
          .<Failure>map(Failure.Rejected::new)
          .orElseGet(() -> new Failure.Permanent("model call failed: " + status));
    }
    if (e instanceof ResourceAccessException access) {
      return access.getCause() instanceof ConnectException
          ? new Failure.Transient("could not reach the model: " + access.getMessage())
          : new Failure.Unknown("no answer from the model: " + access.getMessage());
    }
    return new Failure.Unknown("model call failed: " + e.getMessage());
  }

  /**
   * {@code max_tokens} is omitted rather than sent as zero when no ceiling was asked for -- zero is
   * a real value to this API and would ask for an empty answer.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ChatRequest(
      String model,
      List<WireMessage> messages,
      List<WireTool> tools,
      @JsonProperty("max_tokens") Integer maxTokens) {

    static ChatRequest of(InferenceRequest request) {
      InferenceOptions options = request.options();
      return new ChatRequest(
          options.modelName(),
          // Turn-speak into chat-completions shape: this provider's own translation,
          // and the only place in the engine that knows what a "role" is.
          // A leading system message is how this wire takes a system prompt. Others
          // have a top-level field for it, which is why it arrives as part of the
          // context rather than pre-rendered.
          Stream.concat(
                  Stream.of(new WireMessage("system", system(request))),
                  request.context().turns().stream().flatMap(WireMessage::of))
              .toList(),
          // Omitted entirely rather than sent empty: several OpenAI-compatible servers
          // reject `"tools": []`, and a model offered nothing should be asked exactly
          // the way it was asked before tools existed.
          request.hasTools() ? request.tools().stream().map(WireTool::of).toList() : null,
          options.hasMaxTokens() ? options.maxTokens() : null);
    }
  }

  /**
   * The system prompt, and whatever background stands behind the conversation.
   *
   * <p><b>Folded into the system message, and that is this adapter's decision alone.</b> An
   * OpenAI-compatible endpoint has one place for anything nobody said, so background goes there.
   * Anthropic would put it in the top-level system block; Gemini in a system instruction; an
   * adapter for a wire with a developer role might use that. {@link Ambient} says what the
   * background is and takes no view on any of that.
   *
   * <p>Labelled with tags because it is what the vendors' own guidance asks for, and because a
   * model reading two unlabelled blobs run together cannot tell which is the standing instruction
   * and which is today's note. The kind is safe to interpolate without escaping -- {@code Ambient}
   * constrains it to lowercase kebab-case precisely so that no adapter has to remember to, and none
   * can forget.
   *
   * <p>Sections are omitted entirely when there are none. A heading with nothing under it tells a
   * model its notebook is empty, which is a claim; saying nothing is not.
   */
  private static String system(InferenceRequest request) {
    if (!request.context().hasAmbient()) {
      return request.systemPrompt().value();
    }
    StringBuilder system = new StringBuilder(request.systemPrompt().value());
    for (Ambient ambient : request.context().ambient()) {
      system
          .append("\n\n<")
          .append(ambient.kind())
          .append(">\n")
          .append(WireMessage.text(ambient.content()))
          .append("\n</")
          .append(ambient.kind())
          .append('>');
    }
    return system.toString();
  }

  /**
   * One tool, as this wire describes one.
   *
   * <p>{@code type: "function"} is the only kind this API has ever had, and it is still required on
   * every entry.
   */
  record WireTool(String type, WireFunction function) {

    static WireTool of(ToolOffer offer) {
      return new WireTool(
          "function",
          new WireFunction(offer.name().value(), offer.description(), offer.schema().json()));
    }
  }

  /**
   * {@code parameters} is written through verbatim. The schema is already JSON text, and
   * {@code @JsonRawValue} is what puts it into the body as a document rather than as a string
   * containing one -- parsing it here only to re-serialise it would be work done to produce the
   * same bytes.
   */
  record WireFunction(String name, String description, @JsonRawValue String parameters) {}

  /** One call, as this wire carries one. Arguments are already text on this API. */
  record WireToolCall(String id, String type, WireCallFunction function) {

    static WireToolCall of(Block.ToolCall call) {
      return new WireToolCall(
          call.id().value(),
          "function",
          new WireCallFunction(call.name().value(), call.arguments()));
    }

    Block.ToolCall toBlock() {
      return new Block.ToolCall(id, function.name(), function.arguments());
    }
  }

  record WireCallFunction(String name, String arguments) {}

  /**
   * One message on this wire, in any of the four shapes it takes.
   *
   * <p>Four fields where two were enough before, because tool calling needs an assistant message
   * with calls and no content, and a {@code tool} message quoting the call it answers. Absent
   * fields are omitted rather than sent null -- {@code "tool_call_id": null} on a user message is
   * rejected by several servers that accept its absence.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record WireMessage(
      String role,
      String content,
      @JsonProperty("tool_calls") List<WireToolCall> toolCalls,
      @JsonProperty("tool_call_id") String toolCallId) {

    WireMessage(String role, String content) {
      this(role, content, null, null);
    }

    /** The assistant asking for work: the calls, and whatever it said alongside them. */
    static WireMessage asking(Exchange exchange) {
      return new WireMessage(
          "assistant",
          text(exchange.request()),
          exchange.calls().stream().map(WireToolCall::of).toList(),
          null);
    }

    /**
     * One result, quoting the call it answers.
     *
     * <p>All three outcomes flatten to the same shape here, because that is all this wire has --
     * there is no field for "denied" and no error flag on a {@code tool} message. The distinction
     * is not lost, only unsendable: it stays in the story, and an adapter for a wire that can
     * express it is free to.
     */
    static WireMessage answering(ToolOutcome outcome) {
      return new WireMessage(
          "tool",
          switch (outcome) {
            case ToolOutcome.Succeeded(CallId _, var blocks) -> text(blocks);
            case ToolOutcome.Failed(CallId _, String message) -> "Error: " + message;
            case ToolOutcome.Denied(CallId _, String reason) ->
                "This call was not run because it was not permitted: " + reason;
          },
          null,
          outcome.callId().value());
    }

    /**
     * One turn, as this provider wants to be asked.
     *
     * <p>Every decision here is this adapter's, because only it knows what its wire permits. A turn
     * that produced nothing has to be explained somehow or two user turns end up adjacent with
     * nothing between them, and on an OpenAI-compatible endpoint a mid- conversation {@code system}
     * line is the natural way to do that. It is not available everywhere -- Anthropic's system
     * prompt is a top-level field, not a role -- so an adapter for that wire would fold the same
     * explanation into a turn instead.
     *
     * <p>A refused observation is dropped rather than re-sent: on this provider it is what caused
     * the refusal, and re-sending it keeps the conversation refused for as long as it is still in
     * the request. Whether that is true elsewhere is somebody else's judgement.
     */
    static Stream<WireMessage> of(Turn turn) {
      Stream<WireMessage> opening =
          turn.result() instanceof TurnResult.Refused
              ? Stream.empty()
              : Stream.of(new WireMessage("user", text(turn.observation().blocks())));

      Stream<WireMessage> ending =
          switch (turn.result()) {
            case null -> Stream.of();
            case TurnResult.Answered(var blocks) ->
                Stream.of(new WireMessage("assistant", text(blocks)));
            // "Did not complete" rather than "returned an error", because the call may never
            // have been made at all.
            case TurnResult.Failed _ ->
                Stream.of(
                    new WireMessage("system", "The previous attempt to answer did not complete."));
            // Stands where the withdrawn question stood. Saying nothing would leave two user
            // turns adjacent with no explanation; saying what it was would put back the very
            // content this exists to remove.
            case TurnResult.Refused _ ->
                Stream.of(
                    new WireMessage(
                        "system",
                        "A previous message was withdrawn from this conversation and"
                            + " is no longer available."));
          };
      // Every round, in order, between the question and whatever the model finally said.
      // A call and its result have to stay adjacent and in sequence: this wire rejects an
      // assistant message with calls that is not immediately followed by a result for each
      // of them.
      Stream<WireMessage> middle =
          turn.exchanges().stream()
              .flatMap(
                  exchange ->
                      Stream.concat(
                          Stream.of(WireMessage.asking(exchange)),
                          exchange.outcomes().stream().map(WireMessage::answering)));

      return Stream.concat(opening, Stream.concat(middle, ending));
    }

    /**
     * The text of a run of blocks, and only the text.
     *
     * <p>Anything that is not text is skipped rather than cast. A {@code ToolCall} travels in
     * {@code tool_calls} instead of in the content, and a vendor's reasoning state belongs to
     * whoever attached it -- handing another vendor's bytes to this endpoint would at best be
     * ignored and at worst rejected.
     */
    static String text(List<? extends Block> blocks) {
      return blocks.stream()
          .map(WireMessage::readable)
          .flatMap(Optional::stream)
          .collect(Collectors.joining());
    }

    /**
     * The part of a block a person would read, if any.
     *
     * <p>Commentary is re-sent beside the answer because it is part of what the assistant said, and
     * this wire has one content field for both. A block with nothing readable in it contributes
     * nothing rather than being cast and thrown: a call travels in {@code tool_calls}, and another
     * vendor's reasoning state belongs to whoever attached it.
     *
     * <p>Exhaustive, so a new block kind has to say here whether it is something a person reads.
     * The previous version filtered for {@code Text} and would silently have dropped every one --
     * which is how commentary went missing the first time.
     */
    private static Optional<String> readable(Block block) {
      return switch (block) {
        case Block.Text(String text) -> Optional.of(text);
        case Block.Commentary(String text) -> Optional.of(text);
        case Block.Provider _, Block.ToolCall _ -> Optional.empty();
      };
    }
  }

  record ChatResponse(List<Choice> choices) {}

  record Choice(WireMessage message) {}
}
