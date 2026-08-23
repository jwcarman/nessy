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
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.model.env.EnvModelProviders;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.testing.ScriptedModelProvider;

/**
 * The harness door plus a desk: one DURABLE {@code restart} tool behind {@link
 * UsagePolicy#requireApproval()}, so a call parks until a human decides it. {@code --scripted}
 * drives a short deterministic conversation — no key, no network — that tells the scope one
 * observation, waits for the approval request, approves it, and prints the advertised sentinel once
 * the model's reply lands; without it, this runs a console loop against a real provider from {@link
 * EnvModelProviders#select()}: free text is told to the scope, {@code approve}/{@code deny
 * <reason>} answer whatever's pending, and {@code quit} exits.
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
   * {@link #main} prints. Every wait is bounded: a hung host fails loudly with a named timeout
   * instead of hanging the build.
   *
   * <p>The grant arc (durable-deliveries spec §5a): approving the computation dispatches the call
   * directly past the gate from the grant's own continuation — no re-derivation, no second ask. The
   * notifier fires exactly once, the tool runs exactly once, and the model's reply lands.
   */
  static String runScripted() throws InterruptedException {
    ModelProvider provider = scriptedProvider();
    ModelSettings settings = new ModelSettings("fake-model", SYSTEM_PROMPT, 1024, Set.of(), null);
    BlockingQueue<ApprovalRequest> requests = new LinkedBlockingQueue<>();
    BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    TurnObserver observer =
        TurnObserver.observe(
            o ->
                o.onAssistantSaid(
                    said -> {
                      // the first segment's AssistantSaid carries the tool-use turn, no text yet
                      // (blank); only the post-grant segment's text is the reply worth awaiting.
                      String text = textOf(said.message());
                      if (!text.isBlank()) {
                        replies.add(text);
                      }
                    }));

    Harness<String> harness =
        Nessy.harness(
            h ->
                h.type("approvals")
                    .provider(provider)
                    .settings(settings)
                    .grants(
                        ToolGrant.grant(
                            new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                    .approvalNotifier(request -> printRequest(request, requests))
                    .turnObserver(observer));
    try {
      System.out.println("== posting: please restart prod-eu ==");
      harness.bind(AgentId.of(SCOPE_ID)).observe("please restart prod-eu");

      ApprovalRequest firstAsk = await(requests, "the approval request");
      System.out.println("== approving " + firstAsk.address().approval().value() + " ==");
      harness.approvals().approve(firstAsk.address().approval());

      String reply = await(replies, "the assistant's reply after the grant");
      System.out.println("== assistant replied: " + reply + " ==");
      return "restarted prod-eu: " + reply;
    } finally {
      harness.shutdown();
    }
  }

  private static String textOf(Message message) {
    StringBuilder text = new StringBuilder();
    for (var block : message.content()) {
      if (block instanceof TextBlock(String value)) {
        text.append(value);
      }
    }
    return text.toString();
  }

  private static void runInteractive() throws IOException {
    var selection = EnvModelProviders.select();
    var settings = new ModelSettings(selection.model(), SYSTEM_PROMPT, 1024, Set.of(), null);
    var pending = new LinkedBlockingQueue<ApprovalRequest>();

    // The harness is immortal, not closeable (spec §4): it is kept for the process's lifetime, not
    // shut down when this loop exits.
    Harness<String> harness =
        Nessy.harness(
            h ->
                h.type("approvals")
                    .provider(selection.provider())
                    .settings(settings)
                    .grants(
                        ToolGrant.grant(
                            new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                    .approvalNotifier(request -> printRequest(request, pending))
                    .turnObserver(
                        TurnObserver.observe(
                            o ->
                                o.onAssistantSaid(
                                    said ->
                                        System.out.println("says: " + said.message().content())))));
    var console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    System.out.println("say something ('approve', 'deny <reason>', 'quit'):");
    String line;
    while ((line = console.readLine()) != null) {
      if ("quit".equals(line)) {
        break;
      }
      ApprovalRequest open = pending.peek();
      if ("approve".equals(line) && open != null) {
        harness.approvals().approve(pending.poll().address().approval());
      } else if (line.startsWith("deny ") && open != null) {
        harness.approvals().deny(pending.poll().address().approval(), line.substring(5));
      } else {
        harness.bind(AgentId.of(SCOPE_ID)).observe(line);
      }
    }
  }

  /** Prints the request (computation id + rendered action) and queues it for the loop to answer. */
  private static void printRequest(ApprovalRequest request, BlockingQueue<ApprovalRequest> queue) {
    System.out.println(
        "approval requested: computation="
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
