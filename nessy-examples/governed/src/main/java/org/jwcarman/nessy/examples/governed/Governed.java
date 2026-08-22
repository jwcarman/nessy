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
package org.jwcarman.nessy.examples.governed;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jwcarman.nessy.agent.host.AutonomousHost;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.Impact;
import org.jwcarman.nessy.api.tool.authorization.Likelihood;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;
import org.jwcarman.nessy.api.tool.authorization.RiskFactors;
import org.jwcarman.nessy.api.tool.authorization.RiskLevel;
import org.jwcarman.nessy.api.tool.authorization.RiskPolicies;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.intent.IntentEnricher;
import org.jwcarman.nessy.intent.IntentPolicies;
import org.jwcarman.nessy.intent.IntentStore;
import org.jwcarman.nessy.intent.IntentTool;
import org.jwcarman.nessy.intent.StoredIntentStore;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.store.InMemoryScopedStore;
import org.jwcarman.nessy.testing.ScriptedModelProvider;

/**
 * The full gate as consumer code (vocabulary amendment §3): an org's own sealed intent vocabulary,
 * {@link OpsIntent}, rides the generic {@link IntentTool} kit, and a risk-assessing {@link
 * Enricher} feeds {@link RiskPolicies#threshold}, composed with {@link
 * IntentPolicies#requireDeclared} via {@link UsagePolicy#allOf(List)}. One scripted, deterministic
 * run — no key, no network — narrates every checkpoint of a single turn: a restart with no declared
 * intent is denied in-band (the bounce), the model declares its intent, the retried restart parks
 * for a human (the risk assessment lands in the approval band), a human approves it, and the turn
 * completes.
 */
public final class Governed {

  private static final String SYSTEM_PROMPT = "You are a terse operations assistant.";
  private static final String SCOPE_ID = "prod-eu";
  private static final ActionContributor<RestartInput, String> RESTART_ACTION =
      ActionContributor.named("restart-statement", in -> "restart " + in.target());

  /**
   * What the scripted run observed, for {@code GovernedTest} to assert on beyond the sentinel: the
   * bounce's denial message, the target the model declared, and the final line {@link #main}
   * prints.
   */
  record Result(String bounceMessage, String declaredTarget, String sentinel) {}

  private Governed() {}

  /**
   * Same convention as {@code hello} and {@code approvals}: {@code --scripted} runs the
   * deterministic arc explicitly. Governed has no interactive mode yet, so any other invocation —
   * including no arguments at all — still runs the scripted arc, but says so first rather than
   * silently substituting for a door that doesn't exist yet.
   */
  public static void main(String[] args) throws InterruptedException {
    if (!Arrays.asList(args).contains("--scripted")) {
      System.out.println("governed has no interactive mode yet; running the scripted arc.");
    }
    System.out.println(run().sentinel());
  }

  /**
   * The whole narrated run, factored out so {@code GovernedTest} can assert on the checkpoints
   * {@link #main} only prints. The final checkpoint synchronizes on {@link TurnEvent.TurnEnded} —
   * the autonomous host's default {@code agentObserver} narrates it on the turn observer. Every
   * wait is bounded: a hung host fails loudly with a named timeout instead of hanging the build.
   */
  static Result run() throws InterruptedException {
    var kernel = new InMemoryScopedStore();
    IntentStore<OpsIntent> intentStore = new StoredIntentStore<>(kernel, SCOPE_ID, OpsIntent.class);
    ModelSettings settings = new ModelSettings("fake-model", SYSTEM_PROMPT, 1024, Set.of(), null);
    BlockingQueue<TurnEvent.ToolCallCompleted> toolCompletions = new LinkedBlockingQueue<>();
    BlockingQueue<TurnEvent.TurnEnded> completions = new LinkedBlockingQueue<>();
    BlockingQueue<ApprovalRequest> approvalRequests = new LinkedBlockingQueue<>();
    TurnObserver observer =
        TurnObserver.observe(
            o -> o.onToolCallCompleted(toolCompletions::add).onTurnEnded(completions::add));

    try (AutonomousHost host =
        Nessy.autonomous()
            .type("governed")
            .provider(scriptedProvider())
            .settings(settings)
            .grants(
                ToolGrant.grant(
                    new IntentTool<>(OpsIntent.class, intentStore), UsagePolicy.allow()),
                restartGrant(intentStore))
            .approvalNotifier(approvalRequests::add)
            .turnObserver(observer)
            .store(kernel)
            .build()) {

      System.out.println("== posting: please restart prod-eu ==");
      host.post(SCOPE_ID, "please restart prod-eu");

      TurnEvent.ToolCallCompleted bounce =
          await(toolCompletions, "the bounced restart's completion");
      System.out.println("bounce: " + bounce.result().content());

      await(toolCompletions, "the declare-intent completion");
      OpsIntent declared = intentStore.latest().orElseThrow();
      System.out.println("declared: " + declared);
      String declaredTarget = declared instanceof Restart(String target, _) ? target : null;

      ApprovalRequest request = await(approvalRequests, "the approval request");
      System.out.println(
          "parked: slot="
              + request.address().approval().value()
              + " action="
              + request.context().action().orElse(null));

      host.approvals().approve(request.address().approval());
      System.out.println("approved");

      TurnEvent.ToolCallCompleted completed =
          await(toolCompletions, "the approved restart's completion");
      System.out.println("completion: " + completed.result().content());

      await(completions, "the turn to end");
      return new Result(bounce.result().content(), declaredTarget, "GOVERNED TURN COMPLETE");
    }
  }

  private static ToolGrant restartGrant(IntentStore<OpsIntent> intentStore) {
    List<UsagePolicy> policies =
        List.of(
            IntentPolicies.requireDeclared(OpsIntent.class),
            RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH));
    return ToolGrant.grant(
        new RestartTool(),
        RESTART_ACTION,
        List.of(new IntentEnricher(intentStore), riskAssessor()),
        UsagePolicy.allOf(policies));
  }

  private static Enricher riskAssessor() {
    RiskAssessment assessment =
        RiskAssessment.of(Likelihood.HIGH, Impact.HIGH, RiskFactors.DESTRUCTIVE);
    return Enricher.named("risk", context -> context.with(AuthzContext.RISK_KEY, assessment));
  }

  /**
   * A bounded {@link BlockingQueue#take()}: 30 seconds, then a loud {@link IllegalStateException}
   * naming what never arrived, rather than a hang a CI run has to time out on its own.
   */
  private static <T> T await(BlockingQueue<T> queue, String what) throws InterruptedException {
    T value = queue.poll(30, TimeUnit.SECONDS);
    if (value == null) {
      throw new IllegalStateException("timed out waiting for " + what);
    }
    return value;
  }

  private static ScriptedModelProvider scriptedProvider() {
    ObjectNode restartArguments = JsonNodeFactory.instance.objectNode();
    restartArguments.put("target", "prod-eu");
    ObjectNode declareArguments =
        JsonNodeFactory.instance
            .objectNode()
            .put("type", "Restart")
            .put("target", "prod-eu")
            .put("reason", "stuck deploy");
    return ScriptedModelProvider.script(
        s ->
            s.toolUse("c1", "restart", restartArguments)
                .endWithToolUse()
                .toolUse("c2", "declare-intent", declareArguments)
                .endWithToolUse()
                .toolUse("c3", "restart", restartArguments)
                .endWithToolUse()
                .text("Done — prod-eu restarted.")
                .endTurn());
  }
}
