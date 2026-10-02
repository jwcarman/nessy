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
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.OpenTurns;
import org.jwcarman.nessy.api.TurnId;

/**
 * A chapter policy whose every question is a {@code nessy.chapter.policy} span: whose turns it was
 * shown, and how many turns it closed. A policy may call a model, so what it costs is worth seeing.
 */
public final class ObservedChapterPolicy implements ChapterPolicy {

  public static final String NAME = "nessy.chapter.policy";

  private final ChapterPolicy delegate;
  private final ObservationRegistry observations;

  private ObservedChapterPolicy(ChapterPolicy delegate, ObservationRegistry observations) {
    this.delegate = delegate;
    this.observations = observations;
  }

  /**
   * The policy, observed once: given back unchanged when it is already observed. There is no guard
   * on the registry here: a registry with no handlers YET looks no-op, and a no-op observation
   * costs nothing per call.
   */
  public static ChapterPolicy wrap(ChapterPolicy delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    if (delegate instanceof ObservedChapterPolicy) {
      return delegate;
    }
    return new ObservedChapterPolicy(delegate, observations);
  }

  @Override
  public List<TurnId> ends(OpenTurns open) {
    Observation observation =
        Observation.createNotStarted(NAME, observations)
            .contextualName(NAME)
            .highCardinalityKeyValue("nessy.chapter.open", Integer.toString(open.turns().size()));
    new Identity(open.agentType(), open.agentId()).on(observation, null);
    return observation.observe(
        () -> {
          List<TurnId> ends = delegate.ends(open);
          // A null answer is the keeper's to judge, so it is passed through; it closed nothing.
          observation.highCardinalityKeyValue(
              "nessy.chapter.closed", Integer.toString(ends == null ? 0 : ends.size()));
          return ends;
        });
  }
}
