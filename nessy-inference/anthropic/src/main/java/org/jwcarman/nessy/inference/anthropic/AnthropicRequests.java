package org.jwcarman.nessy.inference.anthropic;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.Capability;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turn-speak into Anthropic's Messages shape.
 *
 * <p>Pure translation, and the only place in this module that knows what a role is. Nothing here
 * touches the network, which is what makes the whole projection testable without a key.
 *
 * <p><b>This wire has two roles and no third.</b> There is no mid-conversation {@code system} line
 * to explain an awkward gap with -- the standing instruction is a top-level field rather than a
 * message -- so where an OpenAI-compatible adapter can narrate around a turn that produced nothing,
 * this one has to answer for it as the assistant or say nothing at all. Those are different
 * judgements about the same story, made here, which is the whole reason an adapter exists.
 */
public final class AnthropicRequests {

  /**
   * How far back the second cache breakpoint is placed.
   *
   * <p>Anthropic allows four; two are used. One moves with the conversation so the newest prefix is
   * written, and one sits further back so that a stable, already-written prefix is still hit on the
   * next turn -- without which the moving one invalidates itself every time.
   */
  private static final int LOOKBACK_BLOCKS = 20;

  private AnthropicRequests() {}

  /** Whether to think, and how much of the budget to allow for it. */
  public record ThinkingConfig(boolean enabled, int budgetTokens) {}

  public static MessageCreateParams toParams(
      InferenceRequest request, ThinkingConfig thinking, JsonMapper mapper) {
    InferenceOptions options = request.options();

    if (thinking.enabled() && options.maxTokens() <= thinking.budgetTokens()) {
      // The budget is spent out of maxTokens, so a ceiling at or below it leaves nothing to
      // answer with. Refused here rather than at the wire, where it is a 400 with no hint.
      throw new IllegalArgumentException(
          "maxTokens (%d) must be greater than the thinking budget (%d)"
              .formatted(options.maxTokens(), thinking.budgetTokens()));
    }

    Optional<CacheControlEphemeral> marker = cacheMarker(options);
    MessageCreateParams.Builder builder =
        MessageCreateParams.builder().model(options.modelName()).maxTokens(options.maxTokens());

    List<TextBlockParam> system = systemBlocks(request, marker);
    if (!system.isEmpty()) {
      builder.systemOfTextBlockParams(system);
    }
    addMessages(builder, request.context().turns(), marker, mapper);
    addTools(builder, request.tools(), marker, mapper);

    if (thinking.enabled()) {
      builder.thinking(
          ThinkingConfigEnabled.builder().budgetTokens(thinking.budgetTokens()).build());
    }
    return builder.build();
  }

  /**
   * Whether this call asked for caching, and for how long.
   *
   * <p>Asked for rather than assumed: caching costs more to write than an ordinary token, so
   * turning it on for a conversation that never repeats a prefix is a bill for nothing.
   */
  private static Optional<CacheControlEphemeral> cacheMarker(InferenceOptions options) {
    if (options.wants(Capability.PROMPT_CACHING_1H)) {
      return Optional.of(
          CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build());
    }
    if (options.wants(Capability.PROMPT_CACHING)) {
      return Optional.of(CacheControlEphemeral.builder().build());
    }
    return Optional.empty();
  }

  /**
   * The standing instruction, and whatever background stands behind the conversation.
   *
   * <p>A top-level field on this wire rather than a leading message, which is why background can be
   * its own labelled block here instead of being concatenated into one string. Marked for caching
   * on the first block, because the system prompt is the longest-lived prefix there is.
   */
  private static List<TextBlockParam> systemBlocks(
      InferenceRequest request, Optional<CacheControlEphemeral> marker) {
    List<TextBlockParam> blocks = new ArrayList<>();
    blocks.add(
        TextBlockParam.builder().text(request.systemPrompt().value()).cacheControl(marker).build());
    for (Ambient ambient : request.context().ambient()) {
      String text = text(ambient.content());
      if (!text.isBlank()) {
        blocks.add(
            TextBlockParam.builder()
                .text("<%s>\n%s\n</%s>".formatted(ambient.kind(), text.strip(), ambient.kind()))
                .build());
      }
    }
    return blocks;
  }

  /** A message on its way to being one: its role, the blocks it came from, and their params. */
  private record Drafted(
      MessageParam.Role role, List<Block> source, List<ContentBlockParam> blocks) {}

  private static void addMessages(
      MessageCreateParams.Builder builder,
      List<Turn> turns,
      Optional<CacheControlEphemeral> marker,
      JsonMapper mapper) {

    List<Drafted> drafts = turns.stream().flatMap(turn -> draft(turn, mapper)).toList();
    Set<Integer> marked = marker.isPresent() ? conversationBreakpoints(drafts) : Set.of();

    List<MessageParam> params = new ArrayList<>(drafts.size());
    int offset = 0;
    for (Drafted draft : drafts) {
      List<ContentBlockParam> blocks = new ArrayList<>(draft.blocks());
      for (int i = 0; i < blocks.size(); i++) {
        if (marked.contains(offset + i)) {
          blocks.set(i, toParam(draft.source().get(i), marker, mapper).orElse(blocks.get(i)));
        }
      }
      offset += blocks.size();
      params.add(MessageParam.builder().role(draft.role()).contentOfBlockParams(blocks).build());
    }
    builder.messages(params);
  }

  /**
   * One turn, as this provider wants to be asked.
   *
   * <p>Roles must alternate, which is what shapes the two awkward cases. A turn that <b>failed</b>
   * gets an assistant message saying so: without one, its question and the next turn's question are
   * two user messages running together, and the model reads that as having been ignored. A turn
   * that was <b>refused</b> is omitted whole -- question included -- because the question is what
   * caused the refusal, and re-sending it keeps the conversation refused for as long as it is in
   * the request. Nothing stands in its place: there is no role here that could say "something was
   * withdrawn" without putting words in the assistant's mouth, and a fabricated utterance is worse
   * than a gap.
   */
  private static Stream<Drafted> draft(Turn turn, JsonMapper mapper) {
    if (turn.result() instanceof TurnResult.Refused) {
      return Stream.of();
    }

    Stream<Drafted> opening =
        draftOf(MessageParam.Role.USER, turn.observation().blocks(), mapper).stream();

    Stream<Drafted> middle =
        turn.exchanges().stream().flatMap(exchange -> draftExchange(exchange, mapper));

    Stream<Drafted> ending =
        switch (turn.result()) {
          case null -> Stream.of();
          case TurnResult.Answered(var blocks) ->
              draftOf(MessageParam.Role.ASSISTANT, blocks, mapper).stream();
          // "Did not complete" rather than "returned an error", because the call may never have
          // been made at all.
          case TurnResult.Failed _ ->
              Stream.of(
                  new Drafted(
                      MessageParam.Role.ASSISTANT,
                      List.of(),
                      List.of(
                          ContentBlockParam.ofText(
                              TextBlockParam.builder()
                                  .text("(The previous attempt to answer did not complete.)")
                                  .build()))));
          case TurnResult.Refused _ -> Stream.of();
        };

    return Stream.concat(opening, Stream.concat(middle, ending));
  }

  /**
   * One round: the assistant asking, then the results coming back as user content.
   *
   * <p>Tool results are user-role on this wire, which is not obvious and is the sort of thing only
   * an adapter should have to know.
   */
  private static Stream<Drafted> draftExchange(Exchange exchange, JsonMapper mapper) {
    Optional<Drafted> asking = draftOf(MessageParam.Role.ASSISTANT, exchange.request(), mapper);
    List<ContentBlockParam> results =
        exchange.outcomes().stream().map(AnthropicRequests::answering).toList();
    Drafted answering = new Drafted(MessageParam.Role.USER, List.of(), results);
    return results.isEmpty()
        ? asking.stream()
        : Stream.concat(asking.stream(), Stream.of(answering));
  }

  /**
   * One result, quoting the call it answers.
   *
   * <p><b>This wire can say a call went wrong, and OpenAI's cannot.</b> {@code is_error} carries
   * both a failure and a denial, because in each case the model is being told the content is not
   * the tool's answer. Marking a denial as a success would offer that sentence as the lake's depth.
   * What the two are is still distinguished in the words, and remains distinguished in the story
   * whatever a wire can carry.
   */
  private static ContentBlockParam answering(ToolOutcome outcome) {
    return switch (outcome) {
      case ToolOutcome.Succeeded(CallId id, var blocks) -> result(id, text(blocks), false);
      case ToolOutcome.Failed(CallId id, String message) -> result(id, message, true);
      case ToolOutcome.Denied(CallId id, String reason) ->
          result(id, "This call was not run because it was not permitted: " + reason, true);
    };
  }

  private static ContentBlockParam result(CallId id, String content, boolean isError) {
    return ContentBlockParam.ofToolResult(
        ToolResultBlockParam.builder()
            .toolUseId(id.value())
            .contentOfBlocks(
                List.of(
                    ToolResultBlockParam.Content.Block.ofText(
                        TextBlockParam.builder().text(content).build())))
            .isError(isError)
            .build());
  }

  private static Optional<Drafted> draftOf(
      MessageParam.Role role, List<? extends Block> content, JsonMapper mapper) {
    List<Block> source = new ArrayList<>();
    List<ContentBlockParam> blocks = new ArrayList<>();
    for (Block block : content) {
      toParam(block, Optional.empty(), mapper)
          .ifPresent(
              param -> {
                source.add(block);
                blocks.add(param);
              });
    }
    return blocks.isEmpty()
        ? Optional.empty()
        : Optional.of(new Drafted(role, List.copyOf(source), List.copyOf(blocks)));
  }

  // ---- cache breakpoints ---------------------------------------------------------------

  /**
   * Where to put the two markers, as positions in the flattened run of blocks.
   *
   * <p>One rides the end of the conversation and one sits {@link #LOOKBACK_BLOCKS} behind it. The
   * trailing one is what actually earns anything: a single moving marker writes a new prefix every
   * turn and reads none of it back, because by the next turn the conversation has moved past it.
   */
  private static Set<Integer> conversationBreakpoints(List<Drafted> drafts) {
    List<Block> flattened = drafts.stream().map(Drafted::source).flatMap(List::stream).toList();
    int moving = lastEligibleAtOrBefore(flattened, flattened.size() - 1);
    if (moving < 0) {
      return Set.of();
    }
    int anchor = lastEligibleAtOrBefore(flattened, moving - LOOKBACK_BLOCKS);
    return anchor < 0 ? Set.of(moving) : Set.of(anchor, moving);
  }

  private static int lastEligibleAtOrBefore(List<Block> blocks, int from) {
    for (int i = Math.min(from, blocks.size() - 1); i >= 0; i--) {
      if (mayCarryCacheControl(blocks.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * A thinking block may not carry cache control -- the vendor rejects it -- so a breakpoint lands
   * on the nearest block before it that may. Exhaustive, so a new block kind has to answer here.
   */
  private static boolean mayCarryCacheControl(Block block) {
    return switch (block) {
      case Block.Text _, Block.Commentary _, Block.ToolCall _ -> true;
      case Block.Provider _ -> false;
    };
  }

  // ---- tools ---------------------------------------------------------------------------

  private static void addTools(
      MessageCreateParams.Builder builder,
      List<ToolOffer> tools,
      Optional<CacheControlEphemeral> marker,
      JsonMapper mapper) {
    for (int i = 0; i < tools.size(); i++) {
      ToolOffer offer = tools.get(i);
      Tool.Builder tool =
          Tool.builder()
              .name(offer.name().value())
              .description(offer.description())
              .inputSchema(AnthropicSchemas.toInputSchema(offer.schema(), mapper));
      // The declarations are one prefix, so only the last one needs marking: the marker covers
      // everything before it.
      if (i == tools.size() - 1) {
        tool.cacheControl(marker);
      }
      builder.addTool(tool.build());
    }
  }

  // ---- blocks --------------------------------------------------------------------------

  private static Optional<ContentBlockParam> toParam(
      Block block, Optional<CacheControlEphemeral> cacheControl, JsonMapper mapper) {
    return switch (block) {
      case Block.Text(String text) ->
          text.isEmpty()
              ? Optional.empty()
              : Optional.of(
                  ContentBlockParam.ofText(
                      TextBlockParam.builder().text(text).cacheControl(cacheControl).build()));
      case Block.Commentary(String text) ->
          text.isEmpty()
              ? Optional.empty()
              : Optional.of(
                  ContentBlockParam.ofText(
                      TextBlockParam.builder().text(text).cacheControl(cacheControl).build()));
      case Block.Provider(String vendor, String payload) -> ours(vendor, payload, mapper);
      case Block.ToolCall(CallId id, var name, String arguments) ->
          Optional.of(
              ContentBlockParam.ofToolUse(
                  ToolUseBlockParam.builder()
                      .id(id.value())
                      .name(name.value())
                      .input(toInput(arguments, mapper))
                      .cacheControl(cacheControl)
                      .build()));
    };
  }

  /**
   * Another vendor's opaque state is not ours to send, and our own must go back exactly as it came.
   *
   * <p>Anthropic's extended thinking is only accepted back with the signature it was issued with --
   * that signature is what says the reasoning was not tampered with -- so a thinking block without
   * one is dropped rather than sent unsigned and rejected.
   */
  private static Optional<ContentBlockParam> ours(
      String vendor, String payload, JsonMapper mapper) {
    if (!AnthropicInferenceProvider.PROVIDER_NAME.equals(vendor)) {
      return Optional.empty();
    }
    Map<String, Object> data = mapper.readValue(payload, new TypeReference<>() {});
    return switch (String.valueOf(data.get("type"))) {
      case "thinking" -> {
        String signature = String.valueOf(data.getOrDefault("signature", ""));
        yield signature.isEmpty()
            ? Optional.empty()
            : Optional.of(
                ContentBlockParam.ofThinking(
                    ThinkingBlockParam.builder()
                        .thinking(String.valueOf(data.getOrDefault("thinking", "")))
                        .signature(signature)
                        .build()));
      }
      case "redacted_thinking" ->
          Optional.of(
              ContentBlockParam.ofRedactedThinking(
                  RedactedThinkingBlockParam.builder()
                      .data(String.valueOf(data.getOrDefault("data", "")))
                      .build()));
      default -> Optional.empty();
    };
  }

  /**
   * A call's arguments are JSON text by the time they reach here, and this wire wants them as
   * properties.
   *
   * <p>Read into plain maps and lists rather than into nodes, because the two sides are on
   * different Jackson majors: this project is on Jackson 3 ({@code tools.jackson}) while the SDK's
   * {@code JsonValue.fromJsonNode} wants a Jackson 2 node. {@code JsonValue.from(Object)} is the
   * bridge that needs neither to know about the other.
   */
  private static ToolUseBlockParam.Input toInput(String arguments, JsonMapper mapper) {
    ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
    Map<String, Object> properties = mapper.readValue(arguments, new TypeReference<>() {});
    properties.forEach(
        (name, value) ->
            input.putAdditionalProperty(name, com.anthropic.core.JsonValue.from(value)));
    return input.build();
  }

  /**
   * The text of a run of blocks, and only the text.
   *
   * <p>Exhaustive, so a new block kind has to say here whether it is something a person reads. A
   * call travels as its own content block rather than in prose, and a vendor's reasoning state
   * belongs to whoever attached it.
   */
  static String text(List<? extends Block> blocks) {
    StringBuilder text = new StringBuilder();
    for (Block block : blocks) {
      switch (block) {
        case Block.Text(String value) -> text.append(value);
        case Block.Commentary(String value) -> text.append(value);
        case Block.Provider _, Block.ToolCall _ -> {
          // Not something a person reads.
        }
      }
    }
    return text.toString();
  }
}
