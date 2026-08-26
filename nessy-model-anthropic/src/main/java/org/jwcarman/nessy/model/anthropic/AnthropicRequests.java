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
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolSpec;
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

    if (!request.systemPrompt().isBlank()) {
      builder.systemOfTextBlockParams(List.of(systemBlock(request.systemPrompt(), marker)));
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
      MessageParam.Role role, List<ContentBlock> source, List<ContentBlockParam> blocks) {}

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
      List<Message> messages,
      Optional<CacheControlEphemeral> marker) {
    List<DraftedMessage> drafts =
        messages.stream().map(AnthropicRequests::draft).flatMap(Optional::stream).toList();
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
    List<ContentBlock> flattened =
        drafts.stream().map(DraftedMessage::source).flatMap(List::stream).toList();
    int moving = lastEligibleAtOrBefore(flattened, flattened.size() - 1);
    if (moving < 0) {
      return Set.of();
    }
    int anchor = lastEligibleAtOrBefore(flattened, moving - LOOKBACK_BLOCKS);
    return anchor < 0 ? Set.of(moving) : Set.of(anchor, moving);
  }

  private static int lastEligibleAtOrBefore(List<ContentBlock> blocks, int from) {
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
  private static boolean mayCarryCacheControl(ContentBlock block) {
    return switch (block) {
      case TextBlock _, ImageBlock _, ToolUseBlock _, ToolResultBlock _ -> true;
      case ThinkingBlock _, RedactedThinkingBlock _ -> false;
    };
  }

  private static void addTools(
      MessageCreateParams.Builder builder,
      List<ToolSpec> tools,
      Optional<CacheControlEphemeral> marker) {
    for (int i = 0; i < tools.size(); i++) {
      var spec = tools.get(i);
      var tool =
          Tool.builder()
              .name(spec.name())
              .description(spec.description())
              .inputSchema(AnthropicSchemas.toInputSchema(spec.inputSchema()));
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
  private static Optional<DraftedMessage> draft(Message message) {
    var role = message.role() == Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
    var source = new ArrayList<ContentBlock>();
    var blocks = new ArrayList<ContentBlockParam>();
    for (ContentBlock block : message.content()) {
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
   * Maps one {@link ContentBlock} to its param form, or nothing at all.
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
      ContentBlock block, Optional<CacheControlEphemeral> cacheControl) {
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
      case ThinkingBlock(String text, String signature) ->
          signature.isEmpty()
              ? Optional.empty()
              : Optional.of(
                  ContentBlockParam.ofThinking(
                      ThinkingBlockParam.builder().thinking(text).signature(signature).build()));
      case RedactedThinkingBlock(String data) ->
          Optional.of(
              ContentBlockParam.ofRedactedThinking(
                  RedactedThinkingBlockParam.builder().data(data).build()));
      case ToolUseBlock(ToolCall call, _) ->
          Optional.of(
              ContentBlockParam.ofToolUse(
                  ToolUseBlockParam.builder()
                      .id(call.id())
                      .name(call.name())
                      .input(toInput(call.arguments()))
                      .cacheControl(cacheControl)
                      .build()));
      case ToolResultBlock(String toolUseId, String content, boolean isError) ->
          Optional.of(
              ContentBlockParam.ofToolResult(
                  ToolResultBlockParam.builder()
                      .toolUseId(toolUseId)
                      .content(content)
                      .isError(isError)
                      .cacheControl(cacheControl)
                      .build()));
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
