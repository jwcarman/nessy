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
package org.jwcarman.nessy.examples.watchman.pekko;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real model, through Nessy's provider.
 *
 * <p>This replaces 165 hand-rolled lines of {@code java.net.http} and JSON assembly that had no
 * streaming, no capability declaration, no prompt caching and — the part that mattered — no way to
 * report what a round COST. Everything here is the provider's: {@code OpenAiModelProvider} builds
 * the request, streams the response, and hands back a {@link Usage} that already accounts for cache
 * reads. What is left is consuming the stream and recording the numbers.
 *
 * <p>Pointed at LM Studio rather than OpenAI, which is a base URL and nothing else — the same move
 * {@code XaiModelProviderBootstrap} makes for x.ai.
 */
public final class ProviderWatchmanModel implements AgentModel {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderWatchmanModel.class);

  private static final String SYSTEM =
      """
      You are the watchman for a single Linux server. Every half hour you do your rounds.

      Use your read-only tools to look at the box: disk_usage and containers. If something needs
      fixing that you cannot fix yourself, propose the tool that would fix it -- prune_images
      removes unused Docker images and REQUIRES a human to approve it, so propose it and do not
      expect it to run during this round. long_job starts a whole-disk trim that takes minutes.

      Call the tools you need, then write one short paragraph of notes about what you found.
      """;

  private final Model model;
  private final MeterRegistry meters;
  private final int maxTokens;

  public ProviderWatchmanModel(Model model, MeterRegistry meters, int maxTokens) {
    this.model = model;
    this.meters = meters;
    this.maxTokens = maxTokens;
  }

  @Override
  public ModelReply reply(Context context) {
    ModelRequest request =
        new ModelRequest(
            context,
            SYSTEM,
            maxTokens,
            WatchmanTools.specs(),
            // Ask for caching. A provider that cannot do it says so and nothing fails -- which is
            // itself the measurement: cache_read stays zero rather than going missing.
            Set.of(Capability.PROMPT_CACHING),
            null);
    try {
      return consume(request);
    } catch (RuntimeException e) {
      LOG.warn("[watchman] the model call failed", e);
      return new ModelReply.Failed(
          Message.assistant(List.of(new TextBlock("the round failed: " + e.getMessage()))),
          Usage.zero(),
          String.valueOf(e));
    }
  }

  private ModelReply consume(ModelRequest request) {
    List<ContentBlock> blocks = new ArrayList<>();
    List<ToolCall> calls = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    Usage usage = Usage.zero();

    try (ModelStream stream = model.stream(request)) {
      for (ModelEvent event : stream) {
        switch (event) {
          case ModelEvent.TextChunk(String chunk) -> text.append(chunk);
          case ModelEvent.ThinkingChunk(String chunk) -> blocks.add(new ThinkingBlock(chunk, null));
          case ModelEvent.ThinkingSigned(String signature) -> {
            // the signature belongs to the thinking block already emitted
          }
          case ModelEvent.RedactedThinkingEmitted(String data) ->
              blocks.add(new RedactedThinkingBlock(data));
          case ModelEvent.ToolUseEmitted(ToolCall call, String signature) -> {
            calls.add(call);
            blocks.add(new ToolUseBlock(call, signature));
          }
          case ModelEvent.TurnEnded(var reason, Usage reported) -> {
            usage = reported;
            LOG.info("[watchman] model finished: {} {}", reason, describe(reported));
          }
        }
      }
    }

    if (!text.isEmpty()) {
      blocks.addFirst(new TextBlock(text.toString()));
    }
    record(usage);

    Message assistant = Message.assistant(List.copyOf(blocks));
    return calls.isEmpty()
        ? new ModelReply.Said(assistant, usage)
        : new ModelReply.AskedForTools(assistant, calls, usage);
  }

  /**
   * The numbers the soak could not see before. Names follow the OpenTelemetry gen_ai semantic
   * conventions, so they line up with what the sibling watchman emits.
   */
  private void record(Usage usage) {
    count("gen_ai.usage.input_tokens", usage.inputTokens());
    count("gen_ai.usage.output_tokens", usage.outputTokens());
    count("gen_ai.usage.cache_read.input_tokens", usage.cacheReadInputTokens());
    count("gen_ai.usage.cache_write.input_tokens", usage.cacheWriteInputTokens());
  }

  private void count(String name, long amount) {
    meters
        .counter(
            name,
            "gen_ai.operation.name",
            "chat",
            "gen_ai.provider.name",
            model.provider(),
            "gen_ai.request.model",
            model.id())
        .increment(amount);
  }

  private static String describe(Usage usage) {
    return "in="
        + usage.inputTokens()
        + " out="
        + usage.outputTokens()
        + " cache_read="
        + usage.cacheReadInputTokens()
        + " cache_write="
        + usage.cacheWriteInputTokens();
  }
}
