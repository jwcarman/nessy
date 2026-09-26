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
package org.jwcarman.nessy.inference;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * What the model may do, and how freely it may choose.
 *
 * <p>The two travel together because neither means anything alone: a choice with nothing on offer
 * is a contradiction, and an offer without a choice leaves the most consequential thing about a
 * call unsaid. Every adapter had been reading both and reconciling them itself, four times over.
 *
 * <p><b>Built once, not per call.</b> A tool's shape cannot change between calls, and varying what
 * is on offer mid-conversation leaves calls in the story for tools the model can no longer see,
 * which reads to it as having imagined them.
 *
 * @param offers every tool as a provider is told about it, and nothing a provider could call
 * @param choice how freely the model may pick, defaulting to its own judgement
 */
public record Toolset(List<ToolOffer> offers, ToolChoice choice) {

  public Toolset {
    Objects.requireNonNull(offers, "offers must not be null");
    // Absent means auto, on the wire and in a stored row alike: a request recorded before there
    // was anything to say about choosing said nothing, which is exactly what auto means.
    // Defaulted rather than refused, because those rows are read back to show what a model was
    // shown.
    choice = choice == null ? ToolChoice.auto() : choice;
    offers = List.copyOf(offers);
    requireCoherent(offers, choice);
  }

  /** Nothing on offer, which is how a model was asked before tools existed at all. */
  public static Toolset none() {
    return new Toolset(List.of(), ToolChoice.auto());
  }

  /** The common case: here they are, use your judgement. */
  public static Toolset of(List<ToolOffer> offers) {
    return new Toolset(offers, ToolChoice.auto());
  }

  /**
   * Whether anything is on offer.
   *
   * <p>Worth asking rather than sending an empty array. Several OpenAI-compatible servers reject
   * {@code "tools": []}, and a model offered nothing should be asked the way it was asked before
   * tools existed.
   */
  public boolean any() {
    return !offers.isEmpty();
  }

  private static void requireCoherent(List<ToolOffer> offers, ToolChoice choice) {
    switch (choice) {
      // Requiring a call with nothing to call is unsatisfiable, and every wire says so in its own
      // words. Caught here so the answer is the same one four times.
      case ToolChoice.Any _ -> {
        if (offers.isEmpty()) {
          throw new IllegalArgumentException("a required tool call needs a tool on offer");
        }
      }
      case ToolChoice.Named(ToolName name) -> {
        if (offers.stream().noneMatch(offer -> offer.name().equals(name))) {
          throw new IllegalArgumentException("no tool named " + name.value() + " is on offer");
        }
      }
      // Auto and None are satisfiable whatever is on offer, including nothing.
      case ToolChoice.Auto _, ToolChoice.None _ -> {
        /* always coherent */
      }
    }
  }
}
