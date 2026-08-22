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
package org.jwcarman.nessy.examples.approvals;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jwcarman.nessy.agent.host.AutonomousHost;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.model.env.EnvModelProviders;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.testing.ScriptedModelProvider;

/**
 * The autonomous door plus a desk: one DURABLE {@code restart} tool behind {@link
 * UsagePolicy#requireApproval()}, so a call parks until a human decides it. {@code --scripted}
 * drives a short deterministic conversation — no key, no network — that posts an observation, waits
 * for the approval request, approves it, and prints the advertised sentinel once the model's reply
 * lands; without it, this runs a console loop against a real provider from {@link
 * EnvModelProviders#select()}: free text posts to the scope, {@code approve}/{@code deny <reason>}
 * answer whatever's pending, and {@code quit} exits.
 */
public final class Approvals {

  private static final String SYSTEM_PROMPT = "You are a terse operations assistant.";
  private static final String SCOPE_ID = "ops";
  private static final ActionContributor<RestartInput, String> RESTART_ACTION =
      ActionContributor.named("restart-statement", in -> "restart " + in.target());

  private Approvals() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    if (contains(Arrays.asList(args), "--scripted")) {
      System.out.println(runScripted());
    } else {
      runInteractive();
    }
  }

  /**
   * The whole scripted arc, factored out so {@code ApprovalsTest} can assert on exactly the line
   * {@link #main} prints. Synchronizes on {@link TurnEvent.TurnEnded} — the autonomous host's
   * default {@code agentObserver} narrates it (and {@link TurnEvent.AssistantSaid}) on the turn
   * observer, so no extra wiring is needed beyond {@link Nessy.AutonomousBuilder#turnObserver}.
   * Every wait is bounded: a hung host fails loudly with a named timeout instead of hanging the
   * build.
   */
  static String runScripted() throws InterruptedException {
    ModelProvider provider = scriptedProvider();
    ModelSettings settings = new ModelSettings("fake-model", SYSTEM_PROMPT, 1024, Set.of(), null);
    BlockingQueue<ApprovalRequest> requests = new LinkedBlockingQueue<>();
    BlockingQueue<TurnEvent.AssistantSaid> replies = new LinkedBlockingQueue<>();
    BlockingQueue<TurnEvent.TurnEnded> completions = new LinkedBlockingQueue<>();
    TurnObserver observer =
        TurnObserver.observe(o -> o.onAssistantSaid(replies::add).onTurnEnded(completions::add));

    try (AutonomousHost<String> host =
        Nessy.autonomous()
            .type("approvals")
            .provider(provider)
            .settings(settings)
            .grants(
                ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
            .approvalNotifier(request -> printRequest(request, requests))
            .turnObserver(observer)
            .build()) {

      System.out.println("== posting: please restart prod-eu ==");
      host.post(SCOPE_ID, "please restart prod-eu");

      ApprovalRequest request = await(requests, "the approval request");
      System.out.println("== approving " + request.address().approval().value() + " ==");
      host.approvals().approve(request.address().approval());

      // The tool-use ask commits to memory alongside its result once the call resolves — both
      // land only after approval, the ask first, then the model's final reply.
      await(replies, "the model's tool-use ask");
      TurnEvent.AssistantSaid said = await(replies, "the model's reply");
      await(completions, "the turn to end");
      String reply =
          said.message().content().getFirst() instanceof TextBlock(String text) ? text : "";
      System.out.println("reply: " + reply);
      return reply + " (APPROVED AND COMPLETE)";
    }
  }

  private static void runInteractive() throws IOException {
    var selection = EnvModelProviders.select();
    var settings = new ModelSettings(selection.model(), SYSTEM_PROMPT, 1024, Set.of(), null);
    var pending = new LinkedBlockingQueue<ApprovalRequest>();

    try (AutonomousHost<String> host =
        Nessy.autonomous()
            .type("approvals")
            .provider(selection.provider())
            .settings(settings)
            .grants(
                ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
            .approvalNotifier(request -> printRequest(request, pending))
            .turnObserver(
                TurnObserver.observe(
                    o ->
                        o.onAssistantSaid(
                            said -> System.out.println("says: " + said.message().content()))))
            .build()) {
      var console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      System.out.println("say something ('approve', 'deny <reason>', 'quit'):");
      String line;
      while ((line = console.readLine()) != null) {
        if ("quit".equals(line)) {
          break;
        }
        ApprovalRequest open = pending.peek();
        if ("approve".equals(line) && open != null) {
          host.approvals().approve(pending.poll().address().approval());
        } else if (line.startsWith("deny ") && open != null) {
          host.approvals().deny(pending.poll().address().approval(), line.substring(5));
        } else {
          host.post(SCOPE_ID, line);
        }
      }
    }
  }

  /** Prints the request (slot id + rendered action) and queues it for the loop to answer. */
  private static void printRequest(ApprovalRequest request, BlockingQueue<ApprovalRequest> queue) {
    System.out.println(
        "approval requested: slot="
            + request.address().approval().value()
            + " action="
            + request.context().action().orElse(null));
    queue.add(request);
  }

  private static ScriptedModelProvider scriptedProvider() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("target", "prod-eu");
    return ScriptedModelProvider.script(
        s ->
            s.toolUse("c1", "restart", arguments)
                .endWithToolUse()
                .text("Restarted prod-eu.")
                .endTurn());
  }

  private static boolean contains(Iterable<String> args, String flag) {
    for (String arg : args) {
      if (flag.equals(arg)) {
        return true;
      }
    }
    return false;
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
}
