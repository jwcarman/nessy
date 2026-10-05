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
import javax.sql.DataSource;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.EmptyInput;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.approval.risk.Impact;
import org.jwcarman.nessy.approval.risk.Likelihood;
import org.jwcarman.nessy.approval.risk.Risk;
import org.jwcarman.nessy.approval.risk.RiskAssessment;
import org.jwcarman.nessy.approval.risk.RiskAssessor;
import org.jwcarman.nessy.approval.risk.RiskFactors;
import org.jwcarman.nessy.approval.risk.RiskLevel;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WatchmanProperties.class)
public class WatchmanConfiguration {

  /** The board's clock: how long an approval request has waited, and when it was answered. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public CommandRunner commandRunner() {
    return new ProcessRunner();
  }

  /**
   * The provider choice IS the switch: naming {@code scripted} both selects this bean over the
   * starter's {@code lmstudio} preset and creates it -- there is nothing else to set.
   */
  @Bean(name = "scripted")
  @ConditionalOnProperty(name = "nessy.provider", havingValue = "scripted")
  public InferenceProvider scripted() {
    return new ScriptedWatchmanProvider(Duration.ofMillis(50));
  }

  @Bean
  public PendingApprovalsRepository pendingApprovals(DataSource dataSource) {
    return new PendingApprovalsRepository(dataSource);
  }

  @Bean
  public InitializingBean watchmanSchema(DataSource dataSource) {
    return () -> PendingApprovalsRepository.initialize(dataSource);
  }

  /**
   * One bean, two roles: the approver the prune is bound to, and -- being the only {@link
   * NarrationListener} declared -- the one the starter hands to the engine.
   */
  @Bean
  public ApprovalsDesk approvalsDesk(PendingApprovalsRepository repository, Clock clock) {
    return new ApprovalsDesk(repository, clock);
  }

  @Bean(name = "watchmanHarness")
  public QueuedHarness<EmptyInput> harness(
      QueuedHarnessFactory factory,
      WatchmanProperties properties,
      CommandRunner runner,
      ApprovalsDesk desk) {
    List<Tool<JsonNode>> tools = WatchmanTools.boundTo(runner);
    return factory.create(
        Watchman.TYPE,
        EmptyInput.class,
        config -> {
          config
              .systemPrompt(WatchmanPrompt.SYSTEM)
              .backlogPolicy(BacklogPolicy.keepLatest())
              // The watchman is only nudged, so the renderer says what the nudge means.
              .inputRenderer(_ -> List.of(new Block.Text("Do your rounds.")))
              .inputLabel(_ -> "rounds");
          // A watchman does rounds forever, so its story grows forever. Nothing is set here for
          // that, so the defaults apply: every twenty turns the engine closes a chapter and has
          // the agent's own model write its summary, and the tail shown whole is capped at the
          // last forty completed turns.
          tools.forEach(
              tool -> {
                if (WatchmanTools.needsApproval(tool.name())) {
                  config.tool(
                      tool,
                      binding ->
                          binding
                              .approver(
                                  gatedOnRisk(tool.name(), desk),
                                  approval -> approval.timeout(properties.getApprovalTerm()))
                              .action(args -> WatchmanTools.actionOf(tool.name())));
                } else {
                  config.tool(
                      tool, binding -> binding.action(args -> WatchmanTools.actionOf(tool.name())));
                }
              });
        });
  }

  static Approver gatedOnRisk(ToolName tool, Approver desk) {
    return Risk.assessing(assessorFor(tool))
        .approvingBelow(RiskLevel.MODERATE)
        .denyingAtOrAbove(RiskLevel.VERY_HIGH)
        .otherwiseAsking(desk);
  }

  private static RiskAssessor assessorFor(ToolName tool) {
    if (tool.value().equals("prune_images")) {
      // Likely to bite -- an image you wanted is only "unused" until you want it -- and the loss
      // is serious rather than catastrophic, because images can be pulled again. The matrix reads
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
}
