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
package org.jwcarman.nessy.agent.host;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.model.env.EnvModelProviders;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Run me from the IDE (needs a provider key in the environment — see {@code nessy-model-env}). Type
 * observations; when the agent wants an approval you'll see the request and the computation id;
 * type "approve" or "deny &lt;reason&gt;" to answer; "quit" exits.
 *
 * <p>Never run by surefire (no {@code @Test} methods, and the class name avoids the default {@code
 * *Test}/{@code *Tests}/{@code *TestCase} include patterns) — this is a tinker door only.
 */
public final class ApprovalPlayground {

  record RestartInput(String target) {}

  static final class RestartTool implements Tool<RestartInput> {

    @Override
    public String name() {
      return "restart_prod";
    }

    @Override
    public String description() {
      return "restarts a production target; requires human approval";
    }

    @Override
    public Class<RestartInput> inputType() {
      return RestartInput.class;
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }

    @Override
    public Awaited<ToolResult> execute(RestartInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("restarted " + input.target()));
    }
  }

  private static final ActionContributor<RestartInput, String> RESTART_ACTION =
      input -> "restart " + input.target();

  private ApprovalPlayground() {}

  public static void main(String[] args) throws Exception {
    var selection = EnvModelProviders.select();
    var settings = new ModelSettings(1024, Set.of(), null);
    var pending = new LinkedBlockingQueue<ApprovalRequest>();
    var harness =
        Nessy.harness(
            h ->
                h.type("playground")
                    .model(selection.model())
                    .systemPrompt("You are a terse assistant.")
                    .settings(settings)
                    .grants(
                        ToolGrant.grant(
                            new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                    .approvalNotifier(pending::add)
                    .turnObserver(event -> System.out.println("  [turn] " + event)));
    var console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    System.out.println("say something ('approve', 'deny <reason>', 'quit'):");
    String line;
    while ((line = console.readLine()) != null) {
      ApprovalRequest open = pending.peek();
      if (line.equals("quit")) {
        break;
      }
      if (line.equals("approve") && open != null) {
        harness.approvals().approve(pending.poll().id());
      } else if (line.startsWith("deny ") && open != null) {
        harness.approvals().deny(pending.poll().id(), line.substring(5));
      } else {
        harness.bind(AgentId.of("tinker")).observe(line);
      }
    }
  }
}
