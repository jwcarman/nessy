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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Summarizes by asking a real model: an ordinary, tool-free call over {@code head} plus the baked-
 * in instructions as a trailing user message. Every request it builds carries an empty system
 * prompt — see {@link Summarizer}'s class javadoc for why — so {@code model} is the only identity
 * this class needs from the agent's own configuration.
 *
 * <p>A blank result is treated as a failure rather than a valid, if useless, summary: an empty
 * summary would still replace the compacted prefix, silently discarding history for nothing.
 *
 * <p>Instruments its own model call as a {@code nessy.model.call} Micrometer observation, with the
 * usage recorded via the {@code gen_ai.usage.*} key-values — the exact span name, contextual name,
 * and attribute keys {@code org.jwcarman.nessy.internal.EngineObservations#modelCall} and {@code
 * #recordUsage} use for the engine's own conversational calls. Those conventions are duplicated
 * here rather than shared because {@code spi.compaction} may not import {@code internal} (see
 * {@code ZoneBoundariesTest}); this is the jurisdiction rule (design §10.6) in code — this call's
 * spend is telemetry's, never {@code ConversationState.usage()}'s.
 */
final class ProviderSummarizer implements Summarizer {

  private final ModelProvider provider;
  private final String model;
  private final int summaryMaxTokens;
  private final String instructions;
  private final ObservationRegistry observations;

  ProviderSummarizer(
      ModelProvider provider,
      String model,
      int summaryMaxTokens,
      String instructions,
      ObservationRegistry observations) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    this.model = model;
    if (summaryMaxTokens < 1) {
      throw new IllegalArgumentException("summaryMaxTokens must be at least 1");
    }
    this.summaryMaxTokens = summaryMaxTokens;
    this.instructions = Objects.requireNonNull(instructions, "instructions must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  @Override
  public String summarize(Context head) {
    Objects.requireNonNull(head, "head must not be null");
    List<Message> messages = new ArrayList<>(head.messages());
    messages.add(Message.user(instructions));
    ModelRequest request =
        new ModelRequest(
            Context.of(messages), "", model, summaryMaxTokens, List.of(), Set.of(), null);
    StringBuilder text = new StringBuilder();
    // Convention source: org.jwcarman.nessy.internal.EngineObservations.modelCall(...).
    Observation observation =
        Observation.start("nessy.model.call", observations)
            .contextualName("chat " + model)
            .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
            .lowCardinalityKeyValue("gen_ai.request.model", model);
    try (var _ = observation.openScope();
        ModelStream stream = provider.stream(request)) {
      for (ModelEvent event : stream) {
        switch (event) {
          case ModelEvent.TextChunk(String chunk) -> text.append(chunk);
          // recordUsage only ever runs from this arm: a stream that ends without a TurnEnded
          // (a provider bug, or the stream closing early) leaves the span with no
          // gen_ai.usage.* key-values at all, rather than a zeroed or partial usage report.
          case ModelEvent.TurnEnded(_, Usage turnUsage) -> recordUsage(observation, turnUsage);
          case ModelEvent.ThinkingChunk _,
              ModelEvent.ThinkingSigned _,
              ModelEvent.RedactedThinkingEmitted _,
              ModelEvent.ToolUseEmitted _ -> {
            // Thinking, redacted-thinking, and tool-use chunks are not part of the summary;
            // this is a tool-free call, so a ToolUseEmitted here would be a provider bug this
            // summarizer doesn't need to guard against specially.
          }
        }
      }
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
    // Checked after the observation has already stopped, on purpose: the model call itself
    // succeeded, so a blank result is this summarizer's own failure, not the call's — the span
    // reports a clean model call, and the thrown IllegalStateException is what Compactor sees.
    String summary = text.toString();
    if (summary.isBlank()) {
      throw new IllegalStateException("summarizer returned no text");
    }
    return summary;
  }

  // Convention source: org.jwcarman.nessy.internal.EngineObservations.recordUsage(...).
  private static void recordUsage(Observation observation, Usage usage) {
    observation
        .highCardinalityKeyValue("gen_ai.usage.input_tokens", Long.toString(usage.inputTokens()))
        .highCardinalityKeyValue("gen_ai.usage.output_tokens", Long.toString(usage.outputTokens()));
  }
}
