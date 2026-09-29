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
package org.jwcarman.nessy.engine.harness;

import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.inference.InferenceProvider;

/**
 * The providers a factory holds, by the names the application gave them.
 *
 * <p>Both doors keep one, and both resolve an agent type's provider against it exactly once, when
 * the harness is built. Nothing downstream of that ever sees an id: the harness is handed the
 * provider itself.
 */
public final class ProviderRegistry {

  private final Map<ProviderId, InferenceProvider> providers = new LinkedHashMap<>();

  /** Two things called {@code openai} is a configuration error, not a preference. */
  public void register(ProviderId id, InferenceProvider provider) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(provider, "provider must not be null");
    if (providers.putIfAbsent(id, provider) != null) {
      throw new IllegalArgumentException("provider '" + id.value() + "' is already registered");
    }
  }

  public List<ProviderId> ids() {
    return List.copyOf(providers.keySet());
  }

  /**
   * Every provider wrapped once, so two agent types naming the same id share one instance and one
   * wrapper.
   */
  public Resolved observed(ObservationRegistry observations) {
    Map<ProviderId, InferenceProvider> wrapped = new LinkedHashMap<>();
    providers.forEach((id, p) -> wrapped.put(id, ObservedInferenceProvider.wrap(p, observations)));
    return new Resolved(Map.copyOf(wrapped), ids());
  }

  /** The registry as a factory holds it once built: observed, and closed to registration. */
  public static final class Resolved {

    private final Map<ProviderId, InferenceProvider> providers;
    private final List<ProviderId> order;

    private Resolved(Map<ProviderId, InferenceProvider> providers, List<ProviderId> order) {
      this.providers = providers;
      this.order = order;
    }

    /**
     * The agent type's own id -- already folded with the factory default by the time it reaches
     * here.
     */
    public ProviderId choose(AgentType agentType, @Nullable ProviderId named) {
      if (named == null) {
        throw new IllegalStateException(
            "agent type '"
                + agentType.value()
                + "' names no provider and the factory has no default; registered: "
                + registered());
      }
      if (!providers.containsKey(named)) {
        throw new IllegalStateException(
            "agent type '"
                + agentType.value()
                + "' names provider '"
                + named.value()
                + "', which is not registered; registered: "
                + registered());
      }
      return named;
    }

    public InferenceProvider resolve(AgentType agentType, @Nullable ProviderId named) {
      return providers.get(choose(agentType, named));
    }

    private String registered() {
      return order.stream().map(ProviderId::value).collect(Collectors.joining(", ", "[", "]"));
    }
  }
}
