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
package org.jwcarman.nessy.examples.toollab;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.Usage;

/**
 * What calls cost, summed count by count. A count the vendor did not report stays unreported when
 * nothing added to it reported one; it is never turned into zero. Where some calls reported a count
 * and others did not, the sum covers the ones that did.
 *
 * @param input all the input processed, cached tokens included
 * @param output the output
 * @param cacheRead the part of the input read from the vendor's cache
 * @param cacheWrite the part of the input written to the vendor's cache
 * @param reasoning the part of the output spent reasoning
 * @param models the models the counts were made on, in the order first seen
 */
record Tally(
    Tokens input,
    Tokens output,
    Tokens cacheRead,
    Tokens cacheWrite,
    Tokens reasoning,
    List<String> models) {

  /** Nothing yet. */
  static final Tally EMPTY =
      new Tally(
          Tokens.none(), Tokens.none(), Tokens.none(), Tokens.none(), Tokens.none(), List.of());

  Tally {
    models = List.copyOf(models);
  }

  Tally plus(Usage usage) {
    return new Tally(
        input.plus(usage.inputTokens()),
        output.plus(usage.outputTokens()),
        cacheRead.plus(usage.cacheReadTokens()),
        cacheWrite.plus(usage.cacheWriteTokens()),
        reasoning.plus(usage.reasoningTokens()),
        withModel(models, usage.model()));
  }

  Tally plus(Tally other) {
    List<String> all = models;
    for (String model : other.models) {
      all = withModel(all, model);
    }
    return new Tally(
        input.plus(other.input),
        output.plus(other.output),
        cacheRead.plus(other.cacheRead),
        cacheWrite.plus(other.cacheWrite),
        reasoning.plus(other.reasoning),
        all);
  }

  /** The count as text: the number, or {@code unreported}. */
  static String show(Tokens tokens) {
    return switch (tokens) {
      case Tokens.Counted(int count) -> Integer.toString(count);
      case Tokens.Uncounted _ -> "unreported";
    };
  }

  private static List<String> withModel(List<String> models, String model) {
    if (model == null || models.contains(model)) {
      return models;
    }
    List<String> more = new ArrayList<>(models);
    more.add(model);
    return more;
  }
}
