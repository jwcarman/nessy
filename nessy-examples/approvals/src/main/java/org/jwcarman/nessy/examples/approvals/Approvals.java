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
import java.time.Duration;
import java.time.Instant;
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
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.model.discovery.ModelDiscovery;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.testing.ScriptedModel;

/**
 * The harness door plus a desk: one DURABLE {@code restart} tool behind an approver that parks, so
 * a call parks until a human decides it. {@code --scripted} drives a short deterministic
 * conversation — no key, no network — that tells the scope one observation, waits for the approval
 * request, approves it, and prints the advertised sentinel once the model's reply lands; without
 * it, this runs a console loop against a real provider from {@link ModelDiscovery#select()}: free
 * text is told to the scope, {@code approve}/{@code deny <reason>} answer whatever's pending, and
 * {@code quit} exits.
 */
public final class Approvals {

  /** How long this demo's questions stand — clipped by the harness's own approval ceiling. */
  private static final Duration DEMO_TERM = Duration.ofDays(7);

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
   * <p>The answer arc (approval-lifecycle spec §5): approving the computation folds one {@code
   * ApprovalAnswered} into the scope, and THAT fold is what emits the run — the desk's delivery
   * never runs a tool itself. The approver parks exactly once, the tool runs exactly once, and the
   * model's reply lands.
   */
  static String runScripted() throws InterruptedException {
    Model model = scriptedModel();
    ModelSettings settings = new ModelSettings(1024, Set.of());
    BlockingQueue<ComputationId> requests = new LinkedBlockingQueue<>();
    BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    TurnObserver observer =
        TurnObserver.observe(
            o ->
                o.onAnswered(
                    said -> {
                      // the first segment's Answered carries the tool-use turn, no text yet
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
                    .model(model)
                    .systemPrompt(SYSTEM_PROMPT)
                    .settings(settings)
                    .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, parking(requests)))
                    .turnObserver(observer));
    try {
      System.out.println("== posting: please restart prod-eu ==");
      harness.bind(AgentId.of(SCOPE_ID)).tell("please restart prod-eu");

      ComputationId firstAsk = await(requests, "the approval request");
      System.out.println("== approving " + firstAsk.value() + " ==");
      harness.approvals().approve(firstAsk, "demo", "");

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
    var selection = ModelDiscovery.select();
    var settings = new ModelSettings(1024, Set.of());
    var pending = new LinkedBlockingQueue<ComputationId>();

    // The harness is immortal, not closeable (spec §4): it is kept for the process's lifetime, not
    // shut down when this loop exits.
    Harness<String> harness =
        Nessy.harness(
            h ->
                h.type("approvals")
                    .model(selection.model())
                    .systemPrompt(SYSTEM_PROMPT)
                    .settings(settings)
                    .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, parking(pending)))
                    .turnObserver(
                        TurnObserver.observe(
                            o ->
                                o.onAnswered(
                                    said ->
                                        System.out.println("says: " + said.message().content())))));
    var console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    System.out.println("say something ('approve', 'deny <reason>', 'quit'):");
    String line;
    while ((line = console.readLine()) != null) {
      if ("quit".equals(line)) {
        break;
      }
      ComputationId open = pending.peek();
      if ("approve".equals(line) && open != null) {
        harness.approvals().approve(pending.poll(), "demo", "");
      } else if (line.startsWith("deny ") && open != null) {
        harness.approvals().deny(pending.poll(), "demo", line.substring(5));
      } else {
        harness.bind(AgentId.of(SCOPE_ID)).tell(line);
      }
    }
  }

  /**
   * The demo's approver: it says how to tell the demo, and the harness runs that once the question
   * has a computation to answer on (deferral-by-callback spec §1). Telling people is still the
   * approver's job — the queue lives behind the approver, not behind a harness-level notifier — but
   * the approver no longer has to park anything itself to do it.
   */
  private static Approver parking(BlockingQueue<ComputationId> queue) {
    return context ->
        ApprovalOutcome.deferred(
            (id, deadline) -> {
              printRequest(context.request(), id, deadline);
              queue.add(id);
            },
            DEMO_TERM);
  }

  /** Prints the parked question (computation id, rendered action, and when it stops standing). */
  private static void printRequest(ApprovalRequest request, ComputationId id, Instant deadline) {
    System.out.println(
        "approval requested: computation="
            + id.value()
            + " action="
            + request.action()
            + " answerable-until="
            + deadline);
  }

  private static ScriptedModel scriptedModel() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("target", "prod-eu");
    return ScriptedModel.script(
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
