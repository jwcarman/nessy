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
package org.jwcarman.nessy.inference.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.VendorProperties;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
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
 *
 * <p><b>Where each stratum of the context lands.</b> The {@code system} field holds the system
 * prompt and nothing else, marked for caching when caching is on and the request is not a one-off.
 * Messages follow in order: summaries, the tail's turns, and the active turn, whose first user
 * message opens with the memory and then the state, each its own text block. Background ends the
 * request: its blocks are appended to the last message when that is a user message, or sit in a
 * user message of their own when it is not.
 *
 * <p>Background is last because it is the stratum that changes while the agent works, and the cache
 * is a prefix. Measured 2026-10-01 on Sonnet 5.5 over thirty calls, background in the system field
 * cost 56k billed input tokens when it never changed and 342k when it changed on every call, as
 * each change rewrote the whole conversation into the cache; a block at the end of the last user
 * message cost 77k to 78k either way. The cache markers are chosen before background is appended,
 * so none sits on it and the cached prefix never contains it.
 */
public final class AnthropicRequests {

  /**
   * What makes a changed prefix survivable.
   *
   * <p>On Fable 5.1, Opus 5.5 and Sonnet 5.5 a thinking block is bound to the system prompt, the
   * tools and every message before it, and a request that replays one after any of those changed is
   * rejected -- by default on accounts created since 2026-08-31. Nessy changes them as a matter of
   * course: the tail slides, and background sits inside the messages rather than ahead of them.
   *
   * <p>With any background present, every replayed thinking block has a changed prefix on every
   * later call, the active turn's own included: a request ends on its message plus the background,
   * and the next call sends that same message without it, with the background on the new last
   * message. The text ahead of the reasoning therefore differs, and the vendor drops all of it
   * (measured 2026-10-01: 193 of 193 replayed blocks dropped with background at the end, none with
   * it in an unchanging system field). Memory and state do the same to a turn once it is finished,
   * since the first message it carried them in is sent without them from then on. {@code
   * drop_block} is what makes this an answer and not a refusal; a block whose prefix is intact is
   * kept.
   *
   * <p>The setting is refused outright unless the request also carries the beta header, which
   * {@link AnthropicInferenceProvider} adds beside whatever betas the client already sends.
   */
  private static final String BLOCK_BINDING = "block_binding";

  private static final String THINKING = "thinking";
  private static final JsonValue BETWEEN_TOOLS = JsonValue.from(Map.of("type", "between_tools"));
  private static final JsonValue DROP_MISMATCHED =
      JsonValue.from(Map.of("prefix_mismatch_behavior", "drop_block"));

  private AnthropicRequests() {}

  /**
   * @param providerProperties the provider's own {@code anthropic.} map -- overlaid here by the
   *     agent type's (spec §7a)
   */
  static MessageCreateParams toParams(
      InferenceRequest request, Map<String, String> providerProperties, JsonMapper mapper) {
    InferenceOptions options = request.options();
    AnthropicPropertyReader.Read read =
        AnthropicPropertyReader.read(
            VendorProperties.merge(providerProperties, options.properties()));
    // Refused here as well as at validate, for a caller that never validated (a summariser).
    AnthropicPropertyReader.requireHeadroom(read, options);

    // A one-off is never sent again, so a marker on it would buy a cache write nobody reads back.
    Optional<CacheControlEphemeral> marker =
        request.oneOff() ? Optional.empty() : read.cacheTtl().map(AnthropicRequests::cacheMarker);
    MessageCreateParams.Builder builder =
        MessageCreateParams.builder().model(options.modelName()).maxTokens(options.maxTokens());

    List<TextBlockParam> system = systemBlocks(request, marker);
    if (!system.isEmpty()) {
      builder.systemOfTextBlockParams(system);
    }
    boolean thinks = read.thinks();
    addMessages(builder, request.context(), marker, thinks, mapper);
    // Answering now is the one choice this vendor cannot be told: measured 2026-09-20, a ban with
    // the tools still in the request ends the turn with no content at all. What works here is not
    // offering them, so that is what this adapter does -- and the cached prefix is the price, paid
    // only on the turns a bound actually fires.
    boolean answering = request.toolset().choice() instanceof ToolChoice.Answer;
    if (!answering) {
      addTools(builder, request.toolset().offers(), marker, mapper);
      chooseTool(builder, request.toolset().offers(), request.toolset().choice());
    }
    request.outputSchema().ifPresent(schema -> askForShape(builder, schema, mapper));

    if (read.enabled()) {
      builder.thinking(
          ThinkingConfigEnabled.builder()
              .budgetTokens(read.budget().getAsInt())
              .putAdditionalProperty(BLOCK_BINDING, DROP_MISMATCHED)
              .build());
    } else if (thinks) {
      builder.thinking(
          ThinkingConfigAdaptive.builder()
              .putAdditionalProperty(BLOCK_BINDING, DROP_MISMATCHED)
              .build());
    } else if (read.betweenTools()) {
      // The SDK has no type for this mode, so the field is sent as the vendor spells it. The
      // request does not think before it responds, so it replays no thinking, like any other that
      // does not.
      builder.putAdditionalBodyProperty(THINKING, BETWEEN_TOOLS);
    }
    read.serviceTier().ifPresent(tier -> builder.serviceTier(serviceTier(tier)));
    return builder.build();
  }

  private static MessageCreateParams.ServiceTier serviceTier(AnthropicServiceTier tier) {
    return switch (tier) {
      case AUTO -> MessageCreateParams.ServiceTier.AUTO;
      case STANDARD_ONLY -> MessageCreateParams.ServiceTier.STANDARD_ONLY;
    };
  }

  /** The cache marker for a ttl: {@code 5m} is today's default marker, {@code 1h} the long one. */
  private static CacheControlEphemeral cacheMarker(AnthropicCacheTtl ttl) {
    return switch (ttl) {
      case FIVE_MINUTES -> CacheControlEphemeral.builder().build();
      case ONE_HOUR ->
          CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build();
    };
  }

  /**
   * The standing instruction, and only that.
   *
   * <p>A top-level field on this wire rather than a leading message. Marked for caching when a
   * marker is given, because the system prompt is the longest-lived prefix there is. Background
   * does not appear here: it follows the last message, so a change in it never changes this field,
   * and so never invalidates the cache that starts with it.
   */
  private static List<TextBlockParam> systemBlocks(
      InferenceRequest request, Optional<CacheControlEphemeral> marker) {
    return List.of(
        TextBlockParam.builder().text(request.systemPrompt().value()).cacheControl(marker).build());
  }

  /** A message on its way to being one: its role and its blocks. */
  private record Drafted(MessageParam.Role role, List<ContentBlockParam> blocks) {}

  private static void addMessages(
      MessageCreateParams.Builder builder,
      InferenceContext context,
      Optional<CacheControlEphemeral> marker,
      boolean thinks,
      JsonMapper mapper) {

    List<Drafted> drafts =
        Stream.of(
                context.summaries().stream().map(AnthropicRequests::draftSummary),
                context.tail().stream().flatMap(turn -> draft(turn, thinks, mapper)),
                withLeadingStrata(draft(context.activeTurn(), thinks, mapper).toList(), context))
            .flatMap(rendered -> rendered)
            .toList();
    Set<Integer> marked = marker.isPresent() ? breakpoints(drafts) : Set.of();

    List<MessageParam> params = new ArrayList<>(drafts.size());
    for (int i = 0; i < drafts.size(); i++) {
      Drafted draft = drafts.get(i);
      List<ContentBlockParam> blocks =
          marked.contains(i)
              ? endingOnMarker(draft.blocks(), marker.orElseThrow())
              : draft.blocks();
      params.add(MessageParam.builder().role(draft.role()).contentOfBlockParams(blocks).build());
    }
    List<ContentBlockParam> background = background(context.ambient());
    if (!background.isEmpty()) {
      appendBackground(params, background);
    }
    builder.messages(params);
  }

  /**
   * The active turn's messages, opening with the memory and then the state.
   *
   * <p>Each is one text block at the head of the turn's first message, ahead of the input's own
   * blocks, so the turn reads as what was recalled, how things stand, and then the question. Only
   * the active turn carries them: they were chosen for it, and a turn that has finished is sent as
   * it was. With neither there is nothing to add and the messages are returned as they were. A turn
   * that drafts to nothing -- a refused one -- is omitted whole, and the memory and state chosen
   * for it go with it.
   */
  private static Stream<Drafted> withLeadingStrata(List<Drafted> active, InferenceContext context) {
    List<ContentBlockParam> leading = new ArrayList<>();
    for (Memory memory : context.memory()) {
      tagged("memory", memory.kind(), memory.content()).ifPresent(leading::add);
    }
    for (State state : context.state()) {
      tagged("state", state.kind(), state.content()).ifPresent(leading::add);
    }
    if (leading.isEmpty() || active.isEmpty()) {
      return active.stream();
    }
    List<ContentBlockParam> opening = new ArrayList<>(leading);
    opening.addAll(active.getFirst().blocks());
    return Stream.concat(
        Stream.of(new Drafted(MessageParam.Role.USER, opening)), active.stream().skip(1));
  }

  /** {@code <memory kind="notes">}, its text and the closing tag, or nothing for blank text. */
  private static Optional<ContentBlockParam> tagged(
      String tag, String kind, List<? extends Block> content) {
    String text = text(content).strip();
    if (text.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        ContentBlockParam.ofText(
            TextBlockParam.builder()
                .text("<%s kind=\"%s\">\n%s\n</%s>".formatted(tag, kind, text, tag))
                .build()));
  }

  /** Each ambient section as its own labelled block, in context order; blank ones are left out. */
  private static List<ContentBlockParam> background(List<Ambient> ambient) {
    List<ContentBlockParam> blocks = new ArrayList<>();
    for (Ambient section : ambient) {
      String text = text(section.content()).strip();
      if (!text.isEmpty()) {
        blocks.add(
            ContentBlockParam.ofText(
                TextBlockParam.builder()
                    .text("<%s>\n%s\n</%s>".formatted(section.kind(), text, section.kind()))
                    .build()));
      }
    }
    return blocks;
  }

  /**
   * Background ends the request: after the last message's own blocks when that message is a user
   * message, or as a user message of its own when the last one is the assistant's, so roles still
   * alternate.
   */
  private static void appendBackground(
      List<MessageParam> params, List<ContentBlockParam> background) {
    int last = params.size() - 1;
    if (last >= 0 && MessageParam.Role.USER.equals(params.get(last).role())) {
      List<ContentBlockParam> blocks = new ArrayList<>(params.get(last).content().asBlockParams());
      blocks.addAll(background);
      params.set(
          last,
          MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build());
    } else {
      params.add(
          MessageParam.builder()
              .role(MessageParam.Role.USER)
              .contentOfBlockParams(background)
              .build());
    }
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
  private static Stream<Drafted> draft(Turn turn, boolean thinks, JsonMapper mapper) {
    if (turn.result() instanceof TurnResult.Refused) {
      return Stream.of();
    }

    Stream<Drafted> opening =
        draftOf(MessageParam.Role.USER, turn.input().blocks(), thinks, mapper).stream();

    Stream<Drafted> middle =
        turn.exchanges().stream().flatMap(exchange -> draftExchange(exchange, thinks, mapper));

    Stream<Drafted> ending =
        switch (turn.result()) {
          case null -> Stream.of();
          case TurnResult.Answered(var blocks) ->
              draftOf(MessageParam.Role.ASSISTANT, blocks, thinks, mapper).stream();
          // "Did not complete" rather than "returned an error", because the call may never have
          // been made at all.
          case TurnResult.Failed _ ->
              Stream.of(
                  new Drafted(
                      MessageParam.Role.ASSISTANT,
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
   * A summary, standing where the turns it replaces once stood.
   *
   * <p>User role and bracketed, as on OpenAI's wire and for the same reason: two roles, neither of
   * which is "here is what happened earlier", and {@code user} is the one that reads as something
   * the model is being shown. The tag and the range tell it from a question, and tell the model
   * which turns are missing.
   *
   * <p>A user-side message like any other where cache markers are concerned: it carries one when it
   * is where a request ends, or where the last one did.
   */
  private static Drafted draftSummary(Summary summary) {
    String text =
        "<summary from=\"%d\" through=\"%d\">\n%s\n</summary>"
            .formatted(
                summary.chapter().from().value(),
                summary.chapter().through().value(),
                summary.text());
    return new Drafted(
        MessageParam.Role.USER,
        List.of(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build())));
  }

  /**
   * One round: the assistant asking, then the results coming back as user content.
   *
   * <p>Tool results are user-role on this wire, which is not obvious and is the sort of thing only
   * an adapter should have to know.
   */
  private static Stream<Drafted> draftExchange(
      Exchange exchange, boolean thinks, JsonMapper mapper) {
    Optional<Drafted> asking =
        draftOf(MessageParam.Role.ASSISTANT, exchange.request(), thinks, mapper);
    List<ContentBlockParam> results =
        exchange.outcomes().stream().map(AnthropicRequests::answering).toList();
    Drafted answering = new Drafted(MessageParam.Role.USER, results);
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

  /**
   * A message's blocks, or nothing when none is left to send.
   *
   * <p>A request that does not think has no use for the reasoning in its history, and carries it
   * under a prefix that no longer matches the one it was signed against -- the case the vendor may
   * refuse, and the one a summariser is in, since it replays an agent's turns under a prompt of its
   * own. Removing every thinking block is valid on this wire, so none is sent; a message that was
   * only reasoning is then left out whole, because an empty one is rejected.
   */
  private static Optional<Drafted> draftOf(
      MessageParam.Role role, List<? extends Block> content, boolean thinks, JsonMapper mapper) {
    List<ContentBlockParam> blocks = new ArrayList<>();
    for (Block block : content) {
      if (!thinks && block instanceof Block.Provider) {
        continue;
      }
      toParam(block, mapper).ifPresent(blocks::add);
    }
    return blocks.isEmpty()
        ? Optional.empty()
        : Optional.of(new Drafted(role, List.copyOf(blocks)));
  }

  // ---- cache breakpoints ---------------------------------------------------------------

  /**
   * Which messages end on a cache marker.
   *
   * <p>Two, and both are settled: the last message, where this request ends, and the user-side
   * message before it, where the last request ended. The engine makes no call to the model until
   * every result of a round is in, so a request it sends ends on a question or a complete set of
   * results. What holds is that the blocks up to and including the marked one do not change within
   * a turn; the message that ends a request does lose its background on the next call, and the
   * active turn's first message loses its memory and state once the turn is in the tail. The second
   * marker sits on the prefix the last request wrote, so the vendor reads it back however many
   * blocks the round in between added; the first alone would depend on the vendor's own search
   * reaching back that far.
   */
  private static Set<Integer> breakpoints(List<Drafted> drafts) {
    int last = drafts.size() - 1;
    if (last < 0) {
      return Set.of();
    }
    for (int i = last - 1; i >= 0; i--) {
      if (MessageParam.Role.USER.equals(drafts.get(i).role())) {
        return Set.of(i, last);
      }
    }
    return Set.of(last);
  }

  /**
   * The same blocks, with the last one that may carry a marker carrying it.
   *
   * <p>A thinking block may not -- the vendor rejects it -- so the marker falls back to the nearest
   * block before it that may. A message with no such block is sent as it was.
   */
  private static List<ContentBlockParam> endingOnMarker(
      List<ContentBlockParam> blocks, CacheControlEphemeral marker) {
    List<ContentBlockParam> marked = new ArrayList<>(blocks);
    for (int i = marked.size() - 1; i >= 0; i--) {
      Optional<ContentBlockParam> carrying = carrying(marked.get(i), marker);
      if (carrying.isPresent()) {
        marked.set(i, carrying.get());
        return marked;
      }
    }
    return marked;
  }

  /** The block with the marker on it, for the three kinds this adapter sends that may carry one. */
  private static Optional<ContentBlockParam> carrying(
      ContentBlockParam block, CacheControlEphemeral marker) {
    if (block.isText()) {
      return Optional.of(
          ContentBlockParam.ofText(block.asText().toBuilder().cacheControl(marker).build()));
    }
    if (block.isToolUse()) {
      return Optional.of(
          ContentBlockParam.ofToolUse(block.asToolUse().toBuilder().cacheControl(marker).build()));
    }
    if (block.isToolResult()) {
      return Optional.of(
          ContentBlockParam.ofToolResult(
              block.asToolResult().toBuilder().cacheControl(marker).build()));
    }
    return Optional.empty();
  }

  // ---- tools ---------------------------------------------------------------------------

  /**
   * How the model is told whether it may reach for what it was offered.
   *
   * <p>Said only when it is not the default and there is something to choose from. A request that
   * offers no tools has nothing to say about choosing one, and an explicit "auto" is what the wire
   * already means when the field is absent -- so every request written before this existed goes out
   * byte for byte as it did.
   */
  private static void chooseTool(
      MessageCreateParams.Builder builder, List<ToolOffer> tools, ToolChoice choice) {
    if (tools.isEmpty()) {
      return;
    }
    switch (choice) {
      case ToolChoice.Auto _ -> {
        // What the absent field already means.
      }
      case ToolChoice.None _ -> builder.toolChoice(ToolChoiceNone.builder().build());
      case ToolChoice.Any _ -> builder.toolChoice(ToolChoiceAny.builder().build());
      case ToolChoice.Named(ToolName name) -> builder.toolToolChoice(name.value());
      // Never reached: a request that means to answer sends no tools, so this method is not
      // called at all. Here because the grammar is sealed and silence would be a guess.
      case ToolChoice.Answer _ ->
          throw new IllegalStateException("answering sends no tools, so no choice to make");
    }
  }

  /**
   * Asks for the answer in a shape, natively.
   *
   * <p>{@code output_config.format} on the stable Messages API -- the parameter that replaced the
   * beta {@code output_format} and needs no beta header. The alternative every other client falls
   * back to (offer a hidden tool whose input schema is the shape, force a call to it, unwrap the
   * arguments) is not needed here, and would have been worse: forcing a tool choice stops a model
   * doing the work it needs to do before it can answer at all.
   */
  private static void askForShape(
      MessageCreateParams.Builder builder, JsonSchema schema, JsonMapper mapper) {
    JsonOutputFormat.Schema.Builder shape = JsonOutputFormat.Schema.builder();
    Map<String, Object> properties = mapper.readValue(schema.json(), new TypeReference<>() {});
    properties.forEach((name, value) -> shape.putAdditionalProperty(name, JsonValue.from(value)));

    builder.outputConfig(
        OutputConfig.builder()
            .format(JsonOutputFormat.builder().schema(shape.build()).build())
            .build());
  }

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

  private static Optional<ContentBlockParam> toParam(Block block, JsonMapper mapper) {
    return switch (block) {
      case Block.Text(String text) ->
          text.isEmpty()
              ? Optional.empty()
              : Optional.of(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()));
      case Block.Commentary(String text) ->
          text.isEmpty()
              ? Optional.empty()
              : Optional.of(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()));
      case Block.Provider(String vendor, String payload) -> ours(vendor, payload, mapper);
      case Block.ToolCall(CallId id, var name, String arguments) ->
          Optional.of(
              ContentBlockParam.ofToolUse(
                  ToolUseBlockParam.builder()
                      .id(id.value())
                      .name(name.value())
                      .input(toInput(arguments, mapper))
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
    if (!AnthropicInferenceProvider.VENDOR.equals(vendor)) {
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
