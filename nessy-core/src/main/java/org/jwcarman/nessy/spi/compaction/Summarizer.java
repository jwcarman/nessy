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

import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * Turns the head of a conversation into prose. The one thing {@code SummarizingCompaction} — the
 * default {@link Compactor} — cannot do itself: call a model.
 *
 * <p>Configuration bakes at construction rather than arriving per call: what to ask for and how
 * much room the reply gets are facts about the summarizer, not about any one summarization.
 *
 * <p>What a summarization call costs is not part of this seam's contract: the jurisdiction rule
 * (design §10.6) reserves {@code ConversationState.usage()} for the loop's own spend, so a
 * summarizer that calls a model instruments that call itself as telemetry rather than returning a
 * bill for the reducer to accumulate. {@link #usingProvider} shows the convention.
 *
 * <p><strong>Behavior change:</strong> the production summarizer never sends a system prompt.
 * Earlier versions inherited the agent's own {@code ModelSettings.systemPrompt()} for its
 * summarization calls, so an agent's persona quietly steered how its own history got summarized.
 * {@link #usingProvider} now takes a bare model name instead of a full {@code ModelSettings}, and
 * every request it builds carries an empty system prompt — the persona no longer reaches the
 * summary.
 */
public interface Summarizer {

  /**
   * The default instructions handed to the summarizer by {@link #usingProvider(ModelProvider,
   * String, ObservationRegistry)}.
   */
  String DEFAULT_INSTRUCTIONS =
      "Summarize the conversation so far for your own future reference: goals, decisions, facts"
          + " established, tool results that matter, and open questions. Be dense and factual;"
          + " omit pleasantries.";

  /**
   * Summarizes {@code head} per this summarizer's baked-in instructions and token budget. Blank
   * text is a producer's own call to make — {@link ProviderSummarizer}, for one, treats it as a
   * failure — this method's contract only promises non-null prose or a thrown exception.
   */
  String summarize(Context head);

  /**
   * The production summarizer: an ordinary, tool-free model call over {@code provider}, using
   * {@code model} and asking for {@code instructions}, capping the reply at {@code
   * summaryMaxTokens}. The request carries no system prompt — see this interface's class javadoc
   * for why. Instruments its own call as a {@code nessy.model.call} Micrometer observation on
   * {@code observations} — the same convention the engine's own conversational calls use — so the
   * summarization call's usage is visible as telemetry without ever reaching the ledger.
   */
  static Summarizer usingProvider(
      ModelProvider provider,
      String model,
      int summaryMaxTokens,
      String instructions,
      ObservationRegistry observations) {
    return new ProviderSummarizer(provider, model, summaryMaxTokens, instructions, observations);
  }

  /**
   * {@link #usingProvider(ModelProvider, String, int, String, ObservationRegistry)} with this
   * codebase's defaults: a 2,048-token summary ceiling and {@link #DEFAULT_INSTRUCTIONS}.
   */
  static Summarizer usingProvider(
      ModelProvider provider, String model, ObservationRegistry observations) {
    return usingProvider(provider, model, 2_048, DEFAULT_INSTRUCTIONS, observations);
  }
}
