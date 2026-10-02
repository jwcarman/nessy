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
package org.jwcarman.nessy.engine.chapter;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.engine.observability.ObservedChapterPolicy;
import org.jwcarman.nessy.engine.observability.ObservedSummarizer;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;

/**
 * What one harness said about its chapters, and the means to build the keeper from it.
 *
 * <p>Shared by both doors' configurations, so a setting means the same thing on each and the rules
 * about it are written once. Chapters are on unless {@link #off()} is called; saying a policy or a
 * summariser turns them back on.
 */
public final class ChapterSettings {

  /** Matches {@code ContextConfig#maxChapterLength}. */
  public static final int DEFAULT_MAX_LENGTH = 30;

  /** Matches {@code HarnessConfig#chapterPolicy}. */
  public static final int DEFAULT_EVERY = 20;

  private boolean on = true;
  private ChapterPolicy policy = ChapterPolicy.every(DEFAULT_EVERY);
  private Summarizer summarizer;
  private int maxLength = DEFAULT_MAX_LENGTH;
  private Duration leaseTtl = ChapterKeeper.DEFAULT_LEASE_TTL;

  public void policy(ChapterPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.on = true;
  }

  public void summarizer(Summarizer summarizer) {
    this.summarizer = Objects.requireNonNull(summarizer, "summarizer must not be null");
    this.on = true;
  }

  public void maxLength(int turns) {
    if (turns < 1) {
      throw new IllegalArgumentException("a chapter holds at least one turn: " + turns);
    }
    this.maxLength = turns;
  }

  public void leaseTtl(Duration ttl) {
    Objects.requireNonNull(ttl, "ttl must not be null");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("the lease time must be positive: " + ttl);
    }
    this.leaseTtl = ttl;
  }

  public void off() {
    this.on = false;
  }

  /** Whether chapters are cut and summarised at all. */
  public boolean on() {
    return on;
  }

  /**
   * Refuses a tail that is not longer than a chapter can be: a chapter could then fall out of the
   * tail before its summary was written, and the model would be shown neither.
   */
  public void requireTail(AgentType agentType, int maxTail) {
    if (on && maxTail <= maxLength) {
      throw new IllegalStateException(
          "agent type '"
              + agentType.value()
              + "': maxTail ("
              + maxTail
              + ") must be greater than maxChapterLength ("
              + maxLength
              + "), or a chapter could be hidden before it is summarised");
    }
  }

  /**
   * The keeper for this harness, or none when chapters are off. The default summariser writes prose
   * with the provider and options this agent type resolved to; it is built only when no summariser
   * was given.
   */
  public Optional<ChapterKeeper> keeper(
      AgentType agentType,
      Chapters chapters,
      Leases leases,
      TurnHistories histories,
      InferenceProvider provider,
      InferenceOptions options,
      ObservationRegistry observations) {
    if (!on) {
      return Optional.empty();
    }
    Summarizer writer =
        summarizer != null ? summarizer : new ProseSummarizer(histories, provider, options);
    return Optional.of(
        new ChapterKeeper(
            agentType,
            ObservedChapterPolicy.wrap(policy, observations),
            ObservedSummarizer.wrap(writer, observations),
            chapters,
            leases,
            histories,
            maxLength,
            leaseTtl));
  }
}
