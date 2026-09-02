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
import org.jwcarman.nessy.api.tool.risk.Impact;
import org.jwcarman.nessy.api.tool.risk.Likelihood;
import org.jwcarman.nessy.api.tool.risk.Risk;
import org.jwcarman.nessy.api.tool.risk.RiskAssessment;
import org.jwcarman.nessy.api.tool.risk.RiskAssessor;
import org.jwcarman.nessy.api.tool.risk.RiskFactors;
import org.jwcarman.nessy.api.tool.risk.RiskLevel;
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
      ModelProvider models,
      org.jwcarman.nessy.spi.memory.TranscriptMemory transcript,
      // Qualified, because the starter contributes an ExecutorService of its own
      // (nessyBlockingExecutor) and by-type injection cannot choose between them. Relying on the
      // parameter name would work only while -parameters is on.
      @org.springframework.beans.factory.annotation.Qualifier("summarizing")
          java.util.concurrent.ExecutorService summarizing,
      javax.sql.DataSource dataSource,
      Clock clock,
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
              .coalescer(WatchmanObservations.COALESCER)
              // A watchman does rounds forever, so its transcript grows forever -- and without
              // this every round would carry every previous round into the model. Older rounds
              // become a paragraph; the last few stay verbatim.
              .memory(
                  org.jwcarman.nessy.memory.summarizing.SummarizingMemory.create(
                      summary ->
                          summary
                              .transcript(transcript)
                              .dataSource(dataSource)
                              .agentType(AgentType.of("watchman"))
                              .model(models.model(ModelId.of(properties.getModelId())))
                              .executor(summarizing)
                              .systemPrompt(WatchmanPrompt.SUMMARIZE)
                              .summarizeAfter(properties.getSummarizeAfter())
                              .keepVerbatim(properties.getKeepVerbatim())
                              .clock(clock)));
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
                              .approver(
                                  Observed.approver(
                                      gatedOnRisk(tool.name(), humanApprover), observations))
                              .action(args -> WatchmanTools.actionOf(tool.name(), args)));
                } else {
                  config.tool(
                      observed,
                      binding -> binding.action(args -> WatchmanTools.actionOf(tool.name(), args)));
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
    return (request) -> {
      listener.expecting(request.callId(), request.replyToken());
      return Awaited.deferred(clock.instant().plus(APPROVAL_TERM));
    };
  }

  /**
   * How risky a call is, and what this box does about it.
   *
   * <p>Two thresholds and a person in between. Below MODERATE runs unasked; at or above VERY_HIGH
   * is refused without waking anybody at 3am; everything else is what the approvals page is for.
   * Those numbers are THIS deployment's appetite — a staging box would set them differently with
   * the same assessor.
   *
   * <p>The assessment lands on the request, so the page can say why it is asking rather than only
   * what it is asking about.
   */
  static Approver gatedOnRisk(String tool, Approver desk) {
    return Risk.assessing(assessorFor(tool))
        .approvingBelow(RiskLevel.MODERATE)
        .denyingAtOrAbove(RiskLevel.VERY_HIGH)
        .otherwiseAsking(desk);
  }

  /**
   * What each gated tool is worth worrying about.
   *
   * <p>Constant per tool, because these risks do not vary with the arguments: {@code docker image
   * prune -af} takes none. A tool whose danger DID depend on its input would read the request
   * instead, which is why an assessor is given the whole question rather than a fixed verdict.
   */
  private static RiskAssessor assessorFor(String tool) {
    if ("prune_images".equals(tool)) {
      // Likely to bite — an image you wanted is only "unused" until you want it — and the loss is
      // serious rather than catastrophic, because images can be pulled again. The matrix reads
      // that pair as MODERATE, which is exactly the middle band: not waved through, not refused
      // outright, so a person decides. WatchmanRiskTest holds that, because a comment claiming a
      // matrix value is a comment that will eventually be wrong.
      return RiskAssessor.always(
          RiskAssessment.of(
              Likelihood.HIGH, Impact.MODERATE, RiskFactors.DESTRUCTIVE, RiskFactors.IRREVERSIBLE));
    }
    // Anything else this box gates but has not assessed: say so rather than assuming it is safe.
    return RiskAssessor.always(
        RiskAssessment.of(Likelihood.MODERATE, Impact.MODERATE, RiskFactors.EXTERNAL_WORLD));
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

  /**
   * Everything the watchman has ever said, whole.
   *
   * <p>The page renders THIS rather than what the model sees, and the difference is the point: the
   * model is given a summary of the older rounds, while a person debugging gets the full history.
   * Summarizing is a sidecar — nothing is deleted to produce it — and this bean is the proof.
   */
  @Bean
  public org.jwcarman.nessy.spi.memory.TranscriptMemory transcript(
      javax.sql.DataSource dataSource) {
    return org.jwcarman.nessy.spi.memory.TranscriptMemory.eternal(
        dataSource, AgentType.of("watchman"));
  }

  /**
   * Where a summary is written, off the thread that noticed it was needed.
   *
   * <p>Its own single thread rather than a shared pool: a vendor call that hangs must not starve
   * anything else, and one summarizer at a time is plenty for one agent. Daemon, so it never holds
   * up a shutdown — a summary that does not finish costs a slightly larger context next round,
   * which is the whole reason this work is safe to abandon.
   */
  @Bean(destroyMethod = "shutdown")
  public java.util.concurrent.ExecutorService summarizing() {
    return java.util.concurrent.Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "watchman-summarizing");
          thread.setDaemon(true);
          return thread;
        });
  }

  @Bean
  public PendingApprovalsListener approvalsListener(
      PendingApprovalsRepository repository, Clock clock) {
    return new PendingApprovalsListener(repository, AgentType.of("watchman"), AGENT, clock);
  }
}
