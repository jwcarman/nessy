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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.Impact;
import org.jwcarman.nessy.api.tool.authorization.Likelihood;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;
import org.jwcarman.nessy.api.tool.authorization.RiskFactors;
import org.jwcarman.nessy.api.tool.authorization.RiskLevel;
import org.jwcarman.nessy.api.tool.authorization.RiskRules;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.intent.IntentEnricher;
import org.jwcarman.nessy.intent.IntentRules;
import org.jwcarman.nessy.intent.IntentStore;
import org.jwcarman.nessy.intent.IntentTool;
import org.jwcarman.nessy.intent.SubstrateIntentStore;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.testing.ScriptedModel;

/**
 * The full gate as consumer code (vocabulary amendment §3): an org's own sealed intent vocabulary,
 * {@link OpsIntent}, rides the generic {@link IntentTool} kit, and a risk-assessing {@link
 * Enricher} feeds {@link RiskRules#threshold}, laddered behind {@link IntentRules#requireDeclared}
 * by {@link Approvers#rules}. One scripted, deterministic run — no key, no network — narrates every
 * checkpoint of a single turn: a restart with no declared intent is denied in-band (the bounce),
 * the model declares its intent, the retried restart parks for a human (the risk assessment lands
 * in the approval band), a human approves it, and the turn completes.
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
   * the harness's default {@code agentObserver} narrates it on the turn observer. Every wait is
   * bounded: a hung host fails loudly with a named timeout instead of hanging the build.
   */
  static Result run() throws InterruptedException {
    var substrate = new InMemorySubstrate();
    ObjectMapper intentMapper =
        new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    IntentStore<OpsIntent> intentStore =
        new SubstrateIntentStore<>(substrate, SCOPE_ID, OpsIntent.class, intentMapper);
    ModelSettings settings = new ModelSettings(1024, Set.of(), null);
    BlockingQueue<TurnEvent.ToolCallCompleted> toolCompletions = new LinkedBlockingQueue<>();
    BlockingQueue<TurnEvent.ToolCallDecided> decisions = new LinkedBlockingQueue<>();
    BlockingQueue<TurnEvent.TurnEnded> completions = new LinkedBlockingQueue<>();
    BlockingQueue<Ask> approvalRequests = new LinkedBlockingQueue<>();
    TurnObserver observer =
        TurnObserver.observe(
            o ->
                o.onToolCallCompleted(toolCompletions::add)
                    .onToolCallDecided(decisions::add)
                    .onTurnEnded(completions::add));

    Harness<String> harness =
        Nessy.harness(
            h ->
                h.type("governed")
                    .model(scriptedModel())
                    .systemPrompt(SYSTEM_PROMPT)
                    .settings(settings)
                    .grants(
                        ToolGrant.grant(
                            new IntentTool<>(OpsIntent.class, intentStore), Approvers.allow()),
                        restartGrant(intentStore, approvalRequests))
                    .turnObserver(observer)
                    .substrate(substrate));
    try {
      System.out.println("== posting: please restart prod-eu ==");
      harness.bind(AgentId.of(SCOPE_ID)).tell("please restart prod-eu");

      // The bounce is an ANSWER now, not a completion (approval-lifecycle spec §3): the approver
      // denies the undeclared restart, and the reducer turns that denial into the error result the
      // model reads.
      String bounceMessage = denialReason(await(decisions, "the bounced restart's denial"));
      System.out.println("bounce: " + bounceMessage);

      await(toolCompletions, "the declare-intent completion");
      OpsIntent declared = intentStore.latest().orElseThrow();
      System.out.println("declared: " + declared);
      String declaredTarget = declared instanceof Restart(String target, _) ? target : null;

      Ask ask = await(approvalRequests, "the approval request");
      System.out.println(
          "parked: computation=" + ask.id().value() + " action=" + ask.request().action());

      harness.approvals().approve(ask.id(), "demo", "");
      System.out.println("approved");

      // The grant arc (durable-deliveries spec §5a): the delivery worker dispatches the call
      // directly past the gate from the grant's own continuation — no second ask, no re-suspend.
      TurnEvent.ToolCallCompleted restarted = await(toolCompletions, "the granted restart");
      System.out.println("restarted: " + restarted.result().content());
      TurnEvent.TurnEnded ended = await(completions, "the turn's completion");
      System.out.println("turn ended: failed=" + ended.failed());
      return new Result(bounceMessage, declaredTarget, "GOVERNED TURN COMPLETE");
    } finally {
      harness.shutdown();
    }
  }

  private static String denialReason(TurnEvent.ToolCallDecided decided) {
    if (decided.approval() instanceof Approval.Denied(String reason, var _)) {
      return reason;
    }
    throw new IllegalStateException("expected a denial, got " + decided.approval());
  }

  /** What the demo's approver tells its queue once it has parked a question. */
  record Ask(ComputationId id, ApprovalRequest request) {}

  private static ToolGrant restartGrant(
      IntentStore<OpsIntent> intentStore, BlockingQueue<Ask> asks) {
    return ToolGrant.grant(
        new RestartTool(),
        RESTART_ACTION,
        List.of(new IntentEnricher<>(intentStore, OpsIntent.class), riskAssessor()),
        queueing(asks));
  }

  /**
   * The demo's approver: the ladder judges, and when it parks, the demo (a queue) is told — telling
   * people is the approver's job (approval-lifecycle spec §1.3).
   */
  private static Approver queueing(BlockingQueue<Ask> asks) {
    Approver ladder =
        Approvers.rules(
            IntentRules.requireDeclared(OpsIntent.class),
            RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH));
    return context -> {
      ApprovalOutcome outcome = ladder.approve(context);
      if (outcome instanceof ApprovalOutcome.Deferred deferred) {
        asks.add(new Ask(deferred.id(), context.request()));
      }
      return outcome;
    };
  }

  private static Enricher riskAssessor() {
    RiskAssessment assessment =
        RiskAssessment.of(Likelihood.HIGH, Impact.HIGH, RiskFactors.DESTRUCTIVE);
    return Enricher.named("risk", draft -> draft.deposit(ApprovalRequest.RISK, assessment));
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

  private static ScriptedModel scriptedModel() {
    ObjectNode restartArguments = JsonNodeFactory.instance.objectNode();
    restartArguments.put("target", "prod-eu");
    ObjectNode declareArguments =
        JsonNodeFactory.instance
            .objectNode()
            .put("type", "Restart")
            .put("target", "prod-eu")
            .put("reason", "stuck deploy");
    return ScriptedModel.script(
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
