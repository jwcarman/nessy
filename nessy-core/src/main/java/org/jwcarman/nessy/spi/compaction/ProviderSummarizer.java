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
package org.jwcarman.nessy.spi.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Summarizes by asking a real model: an ordinary, tool-free call over {@code head} plus the
 * policy's instructions as a trailing user message.
 *
 * <p>A blank result is treated as a failure rather than a valid, if useless, summary: an empty
 * summary would still replace the compacted prefix, silently discarding history for nothing.
 */
final class ProviderSummarizer implements Summarizer {

  private final ModelProvider provider;
  private final ModelSettings config;

  ProviderSummarizer(ModelProvider provider, ModelSettings config) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.config = Objects.requireNonNull(config, "config must not be null");
  }

  @Override
  public Summary summarize(Context head, CompactionPolicy policy) {
    Objects.requireNonNull(head, "head must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    List<Message> messages = new ArrayList<>(head.messages());
    messages.add(Message.user(policy.instructions()));
    ModelRequest request =
        new ModelRequest(
            Context.of(messages),
            config.systemPrompt(),
            config.model(),
            policy.summaryMaxTokens(),
            List.of(),
            Set.of(),
            null);
    StringBuilder text = new StringBuilder();
    Usage usage = Usage.zero();
    try (ModelStream stream = provider.stream(request)) {
      for (ModelEvent event : stream) {
        switch (event) {
          case ModelEvent.TextChunk(String chunk) -> text.append(chunk);
          case ModelEvent.TurnEnded(var _, Usage turnUsage) -> usage = turnUsage;
          // Thinking, redacted-thinking, and tool-use chunks are not part of the summary;
          // this is a tool-free call, so a ToolUseEmitted here would be a provider bug this
          // summarizer doesn't need to guard against specially.
          case ModelEvent.ThinkingChunk _ -> {}
          case ModelEvent.ThinkingSigned _ -> {}
          case ModelEvent.RedactedThinkingEmitted _ -> {}
          case ModelEvent.ToolUseEmitted _ -> {}
        }
      }
    }
    String summary = text.toString();
    if (summary.isBlank()) {
      throw new IllegalStateException("summarizer returned no text");
    }
    return new Summary(summary, usage);
  }
}
