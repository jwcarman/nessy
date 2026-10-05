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
package org.jwcarman.nessy.backend.event;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.TurnId;

/**
 * What a request to the model was made of: the engine that built it, and a reference for each
 * stored part, with the summaries and the turns shown named by the turns they cover. The stored
 * parts themselves are in the payload store; the manifest names them.
 *
 * <p>It is stored with the event that records the model's reply, and nothing in {@code nessy-api}
 * reads it. It is not public API: an application author never sees it, and its shape can change
 * with the engine.
 *
 * <p>A reference is the same whenever the content is the same, so a part that did not change
 * between two calls is the same reference in both manifests.
 *
 * @param engineVersion the version of the engine that built the request
 * @param instructions the system prompt
 * @param tools the tools offered, and the choice made for this call
 * @param answerShape the shape of the answer asked for, when one was asked for
 * @param options the model, the limits and the vendor properties of the call
 * @param summarizedThrough the last turn the summaries shown cover, when any were shown. The
 *     summaries shown are all those of the chapters up to and including this turn; summaries are
 *     written once and never changed, which is what makes one turn id enough to name them.
 * @param tail the turns shown whole, when any were
 * @param memory the sections that came from memory, in the order they were bound
 * @param state the sections that came from state, in the order they were bound
 * @param ambient the sections that came from the surroundings, in the order they were bound
 */
public record RequestManifest(
    String engineVersion,
    PayloadRef instructions,
    PayloadRef tools,
    Optional<PayloadRef> answerShape,
    PayloadRef options,
    Optional<TurnId> summarizedThrough,
    Optional<TurnRange> tail,
    List<Section> memory,
    List<Section> state,
    List<Section> ambient) {

  public RequestManifest {
    Objects.requireNonNull(engineVersion, "engineVersion must not be null");
    Objects.requireNonNull(instructions, "instructions must not be null");
    Objects.requireNonNull(tools, "tools must not be null");
    Objects.requireNonNull(options, "options must not be null");
    answerShape = answerShape == null ? Optional.empty() : answerShape;
    tail = tail == null ? Optional.empty() : tail;
    summarizedThrough = summarizedThrough == null ? Optional.empty() : summarizedThrough;
    memory = memory == null ? List.of() : List.copyOf(memory);
    state = state == null ? List.of() : List.copyOf(state);
    ambient = ambient == null ? List.of() : List.copyOf(ambient);
  }

  /**
   * One section of the context, from a source of one kind. Kinds are not unique, so the order of
   * the list it sits in is part of the record.
   *
   * @param kind what kind of source it came from
   * @param content the section's blocks
   */
  public record Section(String kind, PayloadRef content) {

    public Section {
      Objects.requireNonNull(kind, "kind must not be null");
      if (kind.isBlank()) {
        throw new IllegalArgumentException("a section's kind must name something");
      }
      Objects.requireNonNull(content, "content must not be null");
    }
  }

  /**
   * A run of turns, both ends included.
   *
   * @param from the first turn
   * @param through the last turn, which is not before {@code from}
   */
  public record TurnRange(TurnId from, TurnId through) {

    public TurnRange {
      Objects.requireNonNull(from, "from must not be null");
      Objects.requireNonNull(through, "through must not be null");
      if (through.compareTo(from) < 0) {
        throw new IllegalArgumentException(
            "a range must run forwards: from %s through %s".formatted(from, through));
      }
    }
  }
}
