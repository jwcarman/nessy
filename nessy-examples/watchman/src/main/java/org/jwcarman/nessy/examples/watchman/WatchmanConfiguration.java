package org.jwcarman.nessy.examples.watchman;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.Harness;
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
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spring.boot.Observed;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WatchmanProperties.class)
public class WatchmanConfiguration {

  /** The board's clock: how long a question has waited, and when it was answered. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public CommandRunner commandRunner() {
    return new ProcessRunner();
  }

  /**
   * Declared only when scripted. Otherwise there is no provider bean here, and the OpenAI adapter's
   * auto-configuration builds one from {@code openai.*}.
   */
  @Bean
  @ConditionalOnProperty(name = "watchman.scripted", havingValue = "true")
  public InferenceProvider scriptedProvider() {
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
   * AgentEventListener} declared -- the one the starter hands to the engine.
   */
  @Bean
  public ApprovalsDesk approvalsDesk(PendingApprovalsRepository repository, Clock clock) {
    return new ApprovalsDesk(repository, clock);
  }

  @Bean(name = "watchmanHarness")
  public Harness<String> harness(
      DefaultHarnessFactory factory,
      WatchmanProperties properties,
      CommandRunner runner,
      ApprovalsDesk desk,
      ObservationRegistry observations) {
    List<Tool<JsonNode>> tools = WatchmanTools.boundTo(runner);
    return factory.create(
        String.class,
        config -> {
          config
              .agentType(Watchman.TYPE)
              .systemPrompt(WatchmanPrompt.SYSTEM)
              .observationCoalescer(WatchmanObservations.COALESCER);
          // A watchman does rounds forever, so its story grows forever. The tail the model is
          // shown is capped (the default is the last twenty turns); summarising the head into a
          // paragraph is the piece that has not been rebuilt yet.
          tools.forEach(
              tool -> {
                // Wrapped here rather than by the starter: this application grants its own tools,
                // so it observes its own. Shell commands are the slow part of a round, and a span
                // per call is what makes a twenty-minute round explicable.
                Tool<JsonNode> observed = Observed.tool(tool, observations);
                if (WatchmanTools.needsApproval(tool.name())) {
                  config.tool(
                      observed,
                      binding ->
                          binding
                              .approver(
                                  Observed.approver(gatedOnRisk(tool.name(), desk), observations),
                                  approval -> approval.timeout(properties.getApprovalTerm()))
                              .action(args -> WatchmanTools.actionOf(tool.name())));
                } else {
                  config.tool(
                      observed,
                      binding -> binding.action(args -> WatchmanTools.actionOf(tool.name())));
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
