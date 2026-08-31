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
package org.jwcarman.nessy.model.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ImageBlock;
import org.jwcarman.nessy.api.block.ProviderBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.block.ToolResultContentBlock;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelRequest;

/**
 * Assembles a wire-neutral {@link ModelRequest} into the anthropic-java SDK's {@link
 * MessageCreateParams}.
 *
 * <p>This is pure request assembly: it builds params from a request already fully formed by the
 * harness. It never talks to a client and never sees a key.
 */
public final class AnthropicRequests {

  /**
   * Anthropic's documented per-breakpoint lookback: a breakpoint searches at most twenty content
   * blocks backwards — counting itself as the first — for an existing cache entry, and finds
   * nothing beyond that. It is why one marker on the newest block is not enough for a transcript
   * that can grow by more than twenty blocks in a single turn (a round with several parallel tool
   * calls does exactly that), and it fixes where the second marker goes: exactly one window behind
   * the first, so the two windows abut and their union covers the last thirty-nine blocks with no
   * gap between them.
   */
  private static final int LOOKBACK_BLOCKS = 20;

  private AnthropicRequests() {}

  /**
   * @param enabled whether extended thinking was requested for this call
   * @param budgetTokens the thinking token budget; ignored when {@code enabled} is {@code false}
   */
  public record ThinkingConfig(boolean enabled, int budgetTokens) {}

  /**
   * A blank {@code systemPrompt} omits the {@code system} field entirely rather than sending an
   * empty text block: Anthropic rejects empty text blocks, and an absent system is the correct
   * encoding of "no system prompt".
   */
  public static MessageCreateParams toParams(
      ModelRequest request, String modelId, ThinkingConfig thinking) {
    if (thinking.enabled() && request.maxTokens() <= thinking.budgetTokens()) {
      throw new IllegalArgumentException(
          "maxTokens (%d) must be greater than the thinking budget (%d)"
              .formatted(request.maxTokens(), thinking.budgetTokens()));
    }

    var marker = cacheMarker(request.requested());

    var builder = MessageCreateParams.builder().model(modelId).maxTokens(request.maxTokens());

    List<TextBlockParam> system = systemBlocks(request, marker);
    if (!system.isEmpty()) {
      builder.systemOfTextBlockParams(system);
    }

    addMessages(builder, request.context().messages(), marker);

    addTools(builder, request.tools(), marker);

    if (thinking.enabled()) {
      builder.thinking(
          ThinkingConfigEnabled.builder().budgetTokens(thinking.budgetTokens()).build());
    }

    return builder.build();
  }

  /**
   * The one {@code cache_control} value every breakpoint in this request will carry, or nothing at
   * all when no caching was asked for.
   *
   * <p>{@link Capability#PROMPT_CACHING_1H} selects Anthropic's long entry — {@code
   * "cache_control": {"type": "ephemeral", "ttl": "1h"}} — and, on its own, also turns caching ON:
   * asking for the hour-long entry is asking to cache, and demanding both words would let a caller
   * who listed only the specific one cache nothing while believing they had asked for more.
   *
   * <p>Plain {@link Capability#PROMPT_CACHING} omits {@code ttl} entirely rather than sending
   * {@code "5m"}. The default IS five minutes, so the two are the same request; the shorter one
   * says only what the caller said.
   *
   * <p>One value for the whole request, not one per breakpoint, and that is load-bearing: Anthropic
   * requires that "cache entries with longer TTL must appear before shorter TTLs", so a request
   * that mixed them would have to order its breakpoints by lifetime. All-or-nothing sidesteps that
   * ordering rule entirely.
   */
  private static Optional<CacheControlEphemeral> cacheMarker(Set<Capability> requested) {
    if (requested.contains(Capability.PROMPT_CACHING_1H)) {
      return Optional.of(
          CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build());
    }
    if (requested.contains(Capability.PROMPT_CACHING)) {
      return Optional.of(CacheControlEphemeral.builder().build());
    }
    return Optional.empty();
  }

  /**
   * The standing instruction, then whatever background the context carries.
   *
   * <p>Anthropic has a top-level {@code system} field, so ambient content goes there rather than
   * into the conversation — background is not a turn, and putting it in the message list would make
   * the model answer it instead of the question. Another vendor's adapter is free to decide
   * differently; that is the point of deciding here.
   */
  private static List<TextBlockParam> systemBlocks(
      ModelRequest request, Optional<CacheControlEphemeral> marker) {
    var blocks = new ArrayList<TextBlockParam>();
    if (!request.systemPrompt().isBlank()) {
      blocks.add(systemBlock(request.systemPrompt(), marker));
    }
    for (ContextMessage message : request.context().messages()) {
      if (message instanceof AmbientMessage ambient) {
        String text = textOf(ambient.content());
        if (!text.isBlank()) {
          blocks.add(TextBlockParam.builder().text(text).build());
        }
      }
    }
    return blocks;
  }

  private static String textOf(List<? extends Block> content) {
    var text = new StringBuilder();
    for (Block block : content) {
      if (block instanceof TextBlock(String value)) {
        text.append(value);
      }
    }
    return text.toString();
  }

  private static TextBlockParam systemBlock(
      String systemPrompt, Optional<CacheControlEphemeral> marker) {
    return TextBlockParam.builder().text(systemPrompt).cacheControl(marker).build();
  }

  /**
   * One message reduced to what will actually be sent: the content blocks that survived {@link
   * #toContentBlockParam} and the domain blocks they came from, side by side and index-aligned. The
   * pair is what lets the conversation breakpoints be chosen over the FINAL block positions — the
   * only positions the API will ever see — and then applied by rebuilding just the one or two
   * blocks that carry a marker.
   */
  private record DraftedMessage(
      MessageParam.Role role, List<Block> source, List<ContentBlockParam> blocks) {}

  /**
   * The conversation, with the prompt-cache breakpoints on it (soak finding F1, 2026-08-26).
   *
   * <p>Marking the system prompt and the last tool definition caches a prefix that never grows; the
   * transcript, which is the only part of a long-running agent's request that DOES grow, was marked
   * nowhere, so a watchman round wrote a small entry it could never read back and every round paid
   * full price for the whole history. The fix is the pattern Anthropic documents for a growing
   * conversation: a MOVING breakpoint on the newest block, so each request writes an entry the next
   * request reads, plus an ANCHOR one lookback window behind it for the turns that append more
   * blocks than a single window covers.
   *
   * <p>Two here, plus system and tools, is exactly the four breakpoints a request may carry; a
   * fifth is a 400. Neither marker can land on a thinking block — the API has no {@code
   * cache_control} there, and the SDK's builder does not offer one — so both fall back to the
   * nearest eligible block at or before the position they wanted.
   */
  private static void addMessages(
      MessageCreateParams.Builder builder,
      List<ContextMessage> messages,
      Optional<CacheControlEphemeral> marker) {
    List<DraftedMessage> drafts =
        messages.stream().map(AnthropicRequests::draft).flatMap(List::stream).toList();
    Set<Integer> marked = marker.isPresent() ? conversationBreakpoints(drafts) : Set.of();

    List<MessageParam> params = new ArrayList<>(drafts.size());
    int offset = 0;
    for (DraftedMessage draft : drafts) {
      List<ContentBlockParam> blocks = new ArrayList<>(draft.blocks());
      for (int i = 0; i < blocks.size(); i++) {
        if (marked.contains(offset + i)) {
          blocks.set(i, toContentBlockParam(draft.source().get(i), marker).orElse(blocks.get(i)));
        }
      }
      offset += blocks.size();
      params.add(MessageParam.builder().role(draft.role()).contentOfBlockParams(blocks).build());
    }
    builder.messages(params);
  }

  /**
   * The flattened positions the two conversation breakpoints go on, newest last. Empty when the
   * conversation has no block that may carry one at all.
   */
  private static Set<Integer> conversationBreakpoints(List<DraftedMessage> drafts) {
    List<Block> flattened =
        drafts.stream().map(DraftedMessage::source).flatMap(List::stream).toList();
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
   * Which block kinds may carry a breakpoint. Text, images, tool calls and tool results all may;
   * thinking and redacted thinking may not — the API defines no {@code cache_control} on either,
   * which is visible in the SDK as the absence of the builder method the others have.
   */
  private static boolean mayCarryCacheControl(Block block) {
    return switch (block) {
      case TextBlock _, CommentaryBlock _, ImageBlock _, ToolCallBlock _, ToolResultBlock _ -> true;
      case ProviderBlock _ -> false;
    };
  }

  private static void addTools(
      MessageCreateParams.Builder builder,
      List<org.jwcarman.nessy.api.tool.Tool<?>> tools,
      Optional<CacheControlEphemeral> marker) {
    for (int i = 0; i < tools.size(); i++) {
      var declared = tools.get(i);
      var tool =
          Tool.builder()
              .name(declared.name())
              .description(declared.description())
              .inputSchema(AnthropicSchemas.toInputSchema(declared.inputSchema()));
      if (i == tools.size() - 1) {
        tool.cacheControl(marker);
      }
      builder.addTool(tool.build());
    }
  }

  /**
   * Maps one {@link Message} to a {@link DraftedMessage}, or nothing at all if every one of its
   * content blocks was itself dropped (see {@link #toContentBlockParam}).
   *
   * <p>A message with no representable content is elided outright rather than sent as a param with
   * an empty block list: Anthropic rejects an empty {@code content} array, and — more to the point
   * — a message that translated to nothing carries no information for the model to see. The
   * scenario this guards is a lone unsigned {@link ThinkingBlock}: a resumed session whose thinking
   * was cut off before it was signed settles as an assistant message containing only that one
   * block, which {@link #toContentBlockParam} drops, leaving nothing behind.
   */
  /**
   * One of our messages becomes as many of Anthropic's as its wire needs.
   *
   * <p>An exchange becomes TWO: the assistant turn carrying {@code tool_use}, then a user turn
   * carrying the results — which is Anthropic's encoding, the model said the call and the caller
   * says the answer. It is one value on our side precisely so nothing between here and the
   * transcript can separate them; separating them for the wire is this adapter's job.
   *
   * <p>Ambient content produces nothing here: it went into the system field.
   */
  private static List<DraftedMessage> draft(ContextMessage message) {
    return switch (message) {
      case UserMessage user -> draftOf(MessageParam.Role.USER, user.content()).stream().toList();
      case AnswerMessage answer ->
          draftOf(MessageParam.Role.ASSISTANT, answer.content()).stream().toList();
      case AmbientMessage ignored -> List.of();
      case ExchangeMessage exchange ->
          java.util.stream.Stream.of(
                  draftOf(MessageParam.Role.ASSISTANT, exchange.content()),
                  draftOf(MessageParam.Role.USER, exchange.results()))
              .flatMap(Optional::stream)
              .toList();
    };
  }

  private static Optional<DraftedMessage> draftOf(
      MessageParam.Role role, List<? extends Block> content) {
    var source = new ArrayList<Block>();
    var blocks = new ArrayList<ContentBlockParam>();
    for (Block block : content) {
      toContentBlockParam(block, Optional.empty())
          .ifPresent(
              param -> {
                source.add(block);
                blocks.add(param);
              });
    }
    if (blocks.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new DraftedMessage(role, List.copyOf(source), List.copyOf(blocks)));
  }

  /**
   * State this provider issued, rebuilt into the block it came from.
   *
   * <p>The payload is Anthropic's own shape, kept whole rather than picked apart, so nothing here
   * has to know what a signature means. A block another provider issued is skipped: a transcript
   * outlives a model choice, and a rival's opaque state means nothing on this wire.
   */
  private static Optional<ContentBlockParam> ours(String provider, JsonNode data) {
    if (!AnthropicModelProvider.PROVIDER.equals(provider)) {
      return Optional.empty();
    }
    return switch (data.path("type").asText()) {
      case "thinking" ->
          Optional.of(
              ContentBlockParam.ofThinking(
                  ThinkingBlockParam.builder()
                      .thinking(data.path("thinking").asText())
                      .signature(data.path("signature").asText())
                      .build()));
      case "redacted_thinking" ->
          Optional.of(
              ContentBlockParam.ofRedactedThinking(
                  RedactedThinkingBlockParam.builder().data(data.path("data").asText()).build()));
      default -> Optional.empty();
    };
  }

  /**
   * Maps one {@link Block} to its param form, or nothing at all.
   *
   * <p>An unsigned {@link ThinkingBlock} — signature the empty string — is one block dropped
   * outright: it means the transcript predates response signing, and Anthropic rejects unsigned
   * thinking on replay. A blank {@link TextBlock} is dropped for the same reason {@code
   * systemBlock} never sends one: Anthropic rejects empty text blocks. Every other block
   * round-trips.
   *
   * <p>{@code cacheControl} marks this block as a prompt-cache breakpoint — the request's one
   * marker (see {@link #cacheMarker}) when this block was chosen for one, empty otherwise. It
   * reaches each builder as an {@link Optional} rather than through a branch, so the absent case is
   * spelled once; the two thinking kinds ignore it because the API defines no {@code cache_control}
   * on either, which is why {@link #mayCarryCacheControl} never chooses one.
   */
  private static Optional<ContentBlockParam> toContentBlockParam(
      Block block, Optional<CacheControlEphemeral> cacheControl) {
    return switch (block) {
      case TextBlock(String text) ->
          text.isEmpty()
              ? Optional.empty()
              : Optional.of(
                  ContentBlockParam.ofText(
                      TextBlockParam.builder().text(text).cacheControl(cacheControl).build()));
      case ImageBlock(String mediaType, String base64Data) ->
          Optional.of(
              ContentBlockParam.ofImage(
                  ImageBlockParam.builder()
                      .source(
                          Base64ImageSource.builder()
                              .mediaType(Base64ImageSource.MediaType.of(mediaType))
                              .data(base64Data)
                              .build())
                      .cacheControl(cacheControl)
                      .build()));
      case CommentaryBlock(String text) ->
          text.isEmpty()
              ? Optional.empty()
              : Optional.of(
                  ContentBlockParam.ofText(
                      TextBlockParam.builder().text(text).cacheControl(cacheControl).build()));
      case ProviderBlock(String provider, JsonNode data) -> ours(provider, data);
      case ToolCallBlock(ToolCall call) ->
          Optional.of(
              ContentBlockParam.ofToolUse(
                  ToolUseBlockParam.builder()
                      .id(call.id())
                      .name(call.name())
                      .input(toInput(call.arguments()))
                      .cacheControl(cacheControl)
                      .build()));
      case ToolResultBlock(
              String toolUseId,
              List<ToolResultContentBlock> content,
              boolean isError) ->
          Optional.of(
              ContentBlockParam.ofToolResult(
                  ToolResultBlockParam.builder()
                      .toolUseId(toolUseId)
                      .contentOfBlocks(
                          content.stream().map(AnthropicRequests::toResultBlock).toList())
                      .isError(isError)
                      .cacheControl(cacheControl)
                      .build()));
    };
  }

  /**
   * One block of a tool's answer. Anthropic would take images here too, but {@link
   * ToolResultContentBlock} permits text and nothing else, so this is a total switch over a single
   * arm. The narrowing is the API's, not this adapter's: nothing a tool can legally return is
   * flattened away on the path to the model.
   */
  private static ToolResultBlockParam.Content.Block toResultBlock(ToolResultContentBlock block) {
    return switch (block) {
      case TextBlock(String text) ->
          ToolResultBlockParam.Content.Block.ofText(TextBlockParam.builder().text(text).build());
    };
  }

  private static ToolUseBlockParam.Input toInput(JsonNode arguments) {
    var input = ToolUseBlockParam.Input.builder();
    if (arguments instanceof ObjectNode object) {
      for (var property : object.properties()) {
        input.putAdditionalProperty(property.getKey(), JsonValue.fromJsonNode(property.getValue()));
      }
    }
    return input.build();
  }
}
