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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Told every inference's token counts; reports a call that read fewer cached tokens than the call
 * before it in the same turn.
 *
 * <p>Inside a turn each request is the previous one with more at the end, so the tokens a provider
 * reads from its cache should only grow from call to call. A fall means the request's leading text
 * changed (something ahead of the active turn moved) or the provider's cache entry expired. That is
 * dependable on Anthropic; OpenAI and Gemini cache implicitly and sometimes report no cached tokens
 * for no visible reason, so a fall is a warning and a count, never an error.
 *
 * <p>Each fall is logged at WARN and recorded as a short observation named {@value #FELL}, tagged
 * with the agent type, so any handler attached to the registry can count them. Per agent it
 * remembers the turn and the cache-read count of the last inference that reported one; a new turn
 * starts over, and an inference that reported no count neither reports nor replaces what is
 * remembered. The memory holds at most {@value #MAX_AGENTS} agents, least recently used out first.
 *
 * <p>Nothing here may fail an inference: {@link #saw} does no I/O and does not throw.
 */
public final class CacheWatch {

  /** The observation recorded each time a call reads fewer cached tokens than the one before. */
  public static final String FELL = "nessy.cache.read.fell";

  static final int MAX_AGENTS = 10_000;

  private static final Logger log = LoggerFactory.getLogger(CacheWatch.class);

  private record Last(TurnId turn, int cached) {}

  private final ObservationRegistry observations;

  private final Map<AgentId, Last> remembered =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<AgentId, Last> eldest) {
          return size() > MAX_AGENTS;
        }
      };

  public CacheWatch(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  /** Tells the watch what one inference of this agent's turn read from the provider's cache. */
  public void saw(AgentType agentType, AgentId agentId, TurnId turn, Usage usage) {
    try {
      if (!(usage.cacheReadTokens() instanceof Tokens.Counted(int now))) {
        return;
      }
      Last before;
      synchronized (remembered) {
        before = remembered.put(agentId, new Last(turn, now));
      }
      if (before != null && before.turn().equals(turn) && now < before.cached()) {
        report(agentType, agentId, turn, now, before.cached());
      }
    } catch (RuntimeException e) {
      log.debug("the cache watch could not look at an inference", e);
    }
  }

  private void report(AgentType agentType, AgentId agentId, TurnId turn, int now, int before) {
    log.warn(
        "NESSY CACHE: [{}] agent {} turn {} read {} cached tokens after reading {} on the call"
            + " before; something ahead of the active turn changed, or the provider's cache"
            + " expired",
        agentType.value(),
        agentId.value(),
        turn.value(),
        now,
        before);
    Observation.createNotStarted(FELL, observations)
        .lowCardinalityKeyValue(Identity.AGENT_NAME, agentType.value())
        .start()
        .stop();
  }
}
