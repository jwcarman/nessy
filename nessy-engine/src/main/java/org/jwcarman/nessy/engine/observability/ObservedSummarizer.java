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
package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.turn.Chapter;

/**
 * A summariser whose every chapter is a {@code nessy.summary} span: whose chapter it was, and which
 * turns it covered. The model call inside it is a {@code chat} span like any other, nested here.
 *
 * <p>A summary is written when a turn ends, on a thread of its own, and the engine carries the
 * observation current at that moment onto it, so this span hangs off the turn that triggered it.
 */
public final class ObservedSummarizer implements Summarizer {

  public static final String NAME = "nessy.summary";

  private final Summarizer delegate;
  private final ObservationRegistry observations;

  private ObservedSummarizer(Summarizer delegate, ObservationRegistry observations) {
    this.delegate = delegate;
    this.observations = observations;
  }

  /**
   * The summariser, observed once: given back unchanged when the registry records nothing or when
   * it is already observed.
   */
  public static Summarizer wrap(Summarizer delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    if (observations.isNoop() || delegate instanceof ObservedSummarizer) {
      return delegate;
    }
    return new ObservedSummarizer(delegate, observations);
  }

  @Override
  public String summarize(Chapter chapter) {
    Observation observation =
        Observation.createNotStarted(NAME, observations)
            .contextualName(NAME)
            .highCardinalityKeyValue("nessy.summary.from", Long.toString(chapter.from().value()))
            .highCardinalityKeyValue(
                "nessy.summary.through", Long.toString(chapter.through().value()));
    new Identity(chapter.agentType(), chapter.agentId()).on(observation, null);
    return observation.observe(() -> delegate.summarize(chapter));
  }
}
