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
package org.jwcarman.nessy.examples.watchman;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.engine.PekkoHarnessFactory;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spring.boot.Observed;
import org.jwcarman.nessy.spring.boot.PendingApprovalsListener;
import org.jwcarman.nessy.spring.boot.PendingApprovalsRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What is left of "where Spring meets Pekko" once the starter does it.
 *
 * <p>There is no ActorSystem bean here, no cluster to form, no serializer bindings, no harness to
 * assemble. The starter owns all of that. What remains is what only this application can know: how
 * it reaches a model, what its tools are, which of them needs a person, and where that person is
 * asked.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WatchmanProperties.class)
public class WatchmanConfiguration {

  /** One box, one watchman. */
  public static final AgentId AGENT = AgentId.of(Watchman.AGENT_ID);

  /** How long a proposed command may sit waiting for a person before it is abandoned. */
  private static final Duration APPROVAL_TERM = Duration.ofDays(3);

  @Bean
  public CommandRunner commandRunner() {
    return new ProcessRunner();
  }

  /**
   * The model this watchman talks to: a real OpenAI-compatible endpoint, or a scripted one when
   * {@code watchman.scripted} is set, so the application runs end to end on a box with no model and
   * spends nothing.
   */
  @Bean
  public ModelProvider models(WatchmanProperties properties) {
    if (properties.isScripted()) {
      ScriptedWatchmanModel scripted = new ScriptedWatchmanModel(Duration.ofMillis(50));
      return id -> scripted;
    }
    return OpenAiModelProvider.create(
        config -> config.apiKey(properties.getModelApiKey()).baseUrl(properties.getModelUrl()));
  }

  /**
   * The watchman's only observation kind is text, coalesced so twenty queued ticks are one tick.
   */
  @Bean
  public ObservationRenderer<String> renderer() {
    return WatchmanObservations.RENDERER;
  }

  /**
   * The harness, declared here rather than taken from the starter because this application gates a
   * tool — and an approver is a decision about THIS application's policy, which the starter cannot
   * know.
   */
  @Bean(name = "watchmanHarness")
  public Harness<String> harness(
      PekkoHarnessFactory factory,
      WatchmanProperties properties,
      CommandRunner runner,
      Approver humanApprover,
      io.micrometer.observation.ObservationRegistry observations) {
    List<Tool<com.fasterxml.jackson.databind.JsonNode>> tools = WatchmanTools.boundTo(runner);
    return factory.createHarness(
        String.class,
        config -> {
          config
              .type(AgentType.of("watchman"))
              .systemPrompt(WatchmanPrompt.SYSTEM)
              .model(ModelId.of(properties.getModelId()))
              .renderer(WatchmanObservations.RENDERER)
              .coalescer(WatchmanObservations.COALESCER);
          tools.forEach(
              tool -> {
                // Wrapped here rather than by the starter: this application grants its own tools,
                // so it observes its own. Shell commands are the slow part of a round, and a span
                // per call is what makes a twenty-minute round explicable.
                var observed = Observed.tool(tool, observations);
                if (WatchmanTools.needsApproval(tool.name())) {
                  config.tool(
                      observed,
                      binding ->
                          binding
                              .approver(Observed.approver(humanApprover, observations))
                              .describer(args -> WatchmanTools.describe(tool.name(), args)));
                } else {
                  config.tool(
                      observed,
                      binding ->
                          binding.describer(args -> WatchmanTools.describe(tool.name(), args)));
                }
              });
        });
  }

  /**
   * The approver that puts a question to a person.
   *
   * <p>It does two things and only two: tell the projection where the answer should come back, and
   * say how long the question stands. It never decides — deciding is what the page is for.
   */
  @Bean
  public Approver humanApprover(PendingApprovalsListener listener, Clock clock) {
    return (request, context) -> {
      listener.expecting(request.call().id(), context.replyToken());
      return Awaited.deferred(clock.instant().plus(APPROVAL_TERM));
    };
  }

  /**
   * Applies Nessy's own DDL to the database this application supplied.
   *
   * <p>The engine initializes only a DataSource it CREATED; one an application hands it is never
   * touched uninvited, which is why the shipped file is called {@code nessy-schema.sql} rather than
   * {@code schema.sql} — Boot looks for the latter, so ours never runs by accident and this is the
   * opt-in. A production deployment would more likely apply it through whatever runs its
   * migrations; this is a soak, and one line is the honest version of that.
   *
   * <p>{@code InitializingBean} rather than a listener so it runs before anything reads a table.
   */
  @Bean
  public org.springframework.beans.factory.InitializingBean nessySchema(
      javax.sql.DataSource dataSource) {
    return () -> org.jwcarman.nessy.spi.store.Schemas.initialize(dataSource);
  }

  /** The transcript the page renders, read through the same door the engine writes it with. */
  @Bean
  public org.jwcarman.nessy.api.memory.Memory memory(javax.sql.DataSource dataSource) {
    return org.jwcarman.nessy.spi.memory.TranscriptMemory.eternal(
        dataSource, AgentType.of("watchman"));
  }

  @Bean
  public PendingApprovalsListener approvalsListener(
      PendingApprovalsRepository repository, Clock clock) {
    return new PendingApprovalsListener(repository, AgentType.of("watchman"), AGENT, clock);
  }
}
