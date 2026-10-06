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
package org.jwcarman.nessy.engine.usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ModelUsage;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.StoryProjection;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.UsageReport;
import org.jwcarman.nessy.api.UsageReports;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.engine.story.EventAgentStories;

/**
 * Usage as a projection over the agent's story.
 *
 * <p>Every story event that records an inference carries the usage its provider reported: an
 * answer, a request for actions, a refusal, a failure, and an attempt that was retried. All five
 * count, each as one inference; an inference that cost tokens and then failed still cost them.
 *
 * <p><b>The story lives in one store.</b> An application may hold more than one event store -- the
 * direct and the queued doors each have one -- and on some backends they are two views of the same
 * tables. An agent belongs to one door, so its story is read from the first store that holds any of
 * it, and never from two: reading both would count it twice where they are the same tables.
 */
public final class EventUsageReports implements UsageReports {

  private final List<AgentEvents> stores;

  /**
   * @param stores the event stores to look in, in order
   */
  public EventUsageReports(List<AgentEvents> stores) {
    this.stores = List.copyOf(stores);
  }

  @Override
  public UsageReport of(AgentType type, AgentId id) {
    for (AgentEvents store : stores) {
      Tally tally = EventAgentStories.project(store, type, id, new Spent());
      if (tally.any) {
        return new UsageReport(type, id, new ArrayList<>(tally.byModel.values()), tally.unreported);
      }
    }
    return new UsageReport(type, id, List.of(), 0);
  }

  /** What a story has spent so far, and whether it has any events at all. */
  private static final class Tally {
    private final Map<String, ModelUsage> byModel = new LinkedHashMap<>();
    private int unreported;
    private boolean any;
  }

  /** The projection that sums usage, by model. */
  private static final class Spent implements StoryProjection<Tally> {

    @Override
    public Tally initial() {
      return new Tally();
    }

    @Override
    public Tally apply(Tally soFar, Narrated story) {
      soFar.any = true;
      spent(story.event())
          .ifPresent(
              usage -> {
                if (usage.model() == null) {
                  soFar.unreported++;
                } else {
                  soFar.byModel.merge(usage.model(), first(usage), (sum, one) -> plus(sum, usage));
                }
              });
      return soFar;
    }

    /** The usage a story event records, when it records an inference. */
    private static Optional<Usage> spent(Narration event) {
      return switch (event) {
        case Narration.Answered e -> Optional.of(e.usage());
        case Narration.ActionsRequested e -> Optional.of(e.usage());
        case Narration.TurnRefused e -> Optional.of(e.usage());
        case Narration.TurnFailed e -> Optional.of(e.usage());
        case Narration.InferenceRetried e -> Optional.of(e.usage());
        default -> Optional.empty();
      };
    }

    private static ModelUsage first(Usage usage) {
      return new ModelUsage(
          usage.model(),
          1,
          usage.inputTokens(),
          usage.outputTokens(),
          usage.cacheReadTokens(),
          usage.cacheWriteTokens(),
          usage.reasoningTokens());
    }

    private static ModelUsage plus(ModelUsage sum, Usage usage) {
      return new ModelUsage(
          sum.model(),
          sum.inferences() + 1,
          sum.input().plus(usage.inputTokens()),
          sum.output().plus(usage.outputTokens()),
          sum.cacheRead().plus(usage.cacheReadTokens()),
          sum.cacheWrite().plus(usage.cacheWriteTokens()),
          sum.reasoning().plus(usage.reasoningTokens()));
    }
  }
}
