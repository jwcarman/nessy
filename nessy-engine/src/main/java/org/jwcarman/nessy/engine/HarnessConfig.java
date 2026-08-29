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
package org.jwcarman.nessy.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.agent.ObservationRenderer;

/**
 * What one kind of agent IS — the vocabulary half of the split in engine-extraction spec §2.1.
 *
 * <p><b>Infrastructure is deliberately absent.</b> No {@code Substrate}, no {@code ModelProvider},
 * no {@code ObjectMapper}, no {@code ActorSystem}: those are handed to {@link HarnessFactory} once
 * and shared by every agent it makes. What lands here is what could differ between two agent types
 * running side by side in the same process.
 *
 * <p>That split is what lets this type eventually live in {@code nessy-api} without naming a single
 * SPI type — the reason {@code HarnessConfig} could not simply be moved there today (spec §8).
 *
 * <p>Mutable during customization, read afterwards. A {@link HarnessCustomizer} fills it in; the
 * factory reads it once and builds.
 *
 * @param <O> the observation type these agents accept
 */
public final class HarnessConfig<O> {

  private AgentType type = AgentType.of("agent");
  private String systemPrompt = "";
  private String modelName;
  private ObservationRenderer<O> renderer;
  private Coalescer<O> coalescer = Coalescer.none();
  private Duration approvalTerm = Duration.ofDays(3);
  private int backlogCapacity = 1024;

  HarnessConfig() {}

  /** The agent type — persistence prefix and kind-name root. Defaults to {@code "agent"}. */
  public HarnessConfig<O> type(String typeName) {
    this.type = AgentType.of(typeName);
    return this;
  }

  /** The standing instruction every turn carries. */
  public HarnessConfig<O> systemPrompt(String systemPrompt) {
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    return this;
  }

  /**
   * Which model these agents talk to, BY NAME — resolved against the {@code ModelProvider} the
   * factory holds. Absent means the provider's default.
   *
   * <p>A name rather than a {@code Model} on purpose (spec §2.1): it keeps an SPI type off this
   * surface while still letting a watchman run on something cheap and a planner on something
   * expensive in the same process.
   */
  public HarnessConfig<O> modelName(String modelName) {
    this.modelName = Objects.requireNonNull(modelName, "modelName must not be null");
    return this;
  }

  /**
   * How an observation becomes inference content. Required for any {@code O} but {@code String}.
   */
  public HarnessConfig<O> renderer(ObservationRenderer<O> renderer) {
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    return this;
  }

  /**
   * How a waiting backlog is groomed as observations arrive. Defaults to {@link Coalescer#none()},
   * which merges nothing.
   */
  public HarnessConfig<O> coalescer(Coalescer<O> coalescer) {
    this.coalescer = Objects.requireNonNull(coalescer, "coalescer must not be null");
    return this;
  }

  /**
   * How long a tool call may sit waiting on a human before it is abandoned. Defaults to three days.
   *
   * <p>Claim expiry is derived from this rather than hardcoded (composition spec §8a): expire a
   * claim sooner than the longest legitimate park and the arguments of a call a human is about to
   * approve are gone, which looks like a broken approval rather than a retention bug.
   */
  public HarnessConfig<O> approvalTerm(Duration approvalTerm) {
    this.approvalTerm = Objects.requireNonNull(approvalTerm, "approvalTerm must not be null");
    return this;
  }

  /**
   * How many observations may wait before arrivals are dropped. Defaults to 1024.
   *
   * <p>A drop is counted, never merely logged — it is the one moment the system knowingly loses
   * information, and a metric nobody greps for is how backpressure gets discovered from a confused
   * user instead of a graph.
   */
  public HarnessConfig<O> backlogCapacity(int backlogCapacity) {
    if (backlogCapacity < 1) {
      throw new IllegalArgumentException("backlogCapacity must be at least 1");
    }
    this.backlogCapacity = backlogCapacity;
    return this;
  }

  AgentType type() {
    return type;
  }

  String systemPrompt() {
    return systemPrompt;
  }

  Optional<String> modelName() {
    return Optional.ofNullable(modelName);
  }

  ObservationRenderer<O> renderer() {
    return renderer;
  }

  Coalescer<O> coalescer() {
    return coalescer;
  }

  Duration approvalTerm() {
    return approvalTerm;
  }

  int backlogCapacity() {
    return backlogCapacity;
  }
}
