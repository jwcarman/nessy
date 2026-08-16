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
package org.jwcarman.nessy.examples;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Function;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.console.ConsoleApprover;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.notebook.Notebook;
import org.jwcarman.nessy.spi.notebook.NotebookTools;
import org.jwcarman.nessy.spi.plan.PlanStore;
import org.jwcarman.nessy.spi.plan.PlanTools;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * The one agent definition {@code Chat}'s single main drives, whichever provider {@link
 * org.jwcarman.nessy.model.env.EnvModelProviders#fromEnv()} hands it.
 *
 * <p>Pattern demonstrated: two watching surfaces, not one narrating the same fact twice. {@link
 * org.jwcarman.nessy.console.ConsoleRepl}'s default renderer narrates one turn <em>live</em> —
 * deltas as they stream, a dim {@code ⚙ tool:} line the instant a call is requested or completed —
 * via the {@code TurnObserver} {@link org.jwcarman.nessy.Conversation#tell(Object,
 * org.jwcarman.nessy.api.turn.TurnObserver)} hands it. {@link ConversationEvent}, by contrast, is
 * the <em>settled</em> fact-log side of the story (this was {@code AnthropicChat}'s lesson,
 * formerly attached per-conversation via {@code Conversation#events()}). {@code ConsoleRepl} now
 * owns conversation construction end to end — one conversation is built inside its own {@code
 * run()}, with no instance handed back to the caller — so there is no live {@code Conversation}
 * left at this call site to attach a per-conversation {@code events()} subscription to. The
 * equivalent channel survives here as a build-time {@link
 * org.jwcarman.nessy.AgentBuilder#listen(Class, java.util.function.Consumer) listen} declaration
 * instead: the same {@link org.jwcarman.nessy.api.event.ListenerRegistry} delivery, just declared
 * once on the agent rather than attached once per conversation. It announces {@link
 * ConversationEvent.ModelResponded}'s token usage — a fact the turn narration never shows — rather
 * than {@link ConversationEvent.ToolFinished}, which {@code AnthropicChat} used to announce: {@code
 * ConsoleRenderer}'s default already prints a dim {@code ⚙ tool: <name> completed} line for that
 * same fact, so re-announcing it here would narrate the same completion twice.
 */
public final class DemoAgent {

  private static final String SYSTEM_PROMPT =
      "You are Nessy's demo assistant. You can add numbers and tell the current time. Be brief."
          + " For multi-step requests, maintain a task list with update_plan. When the user tells"
          + " you something worth keeping, remember it.";

  private DemoAgent() {}

  /**
   * Builds the demo agent — the same identity whichever provider the environment handed us. Also
   * the family's first demonstration of tool-writable, recall-injected context (spec §1): the model
   * maintains its own plan through {@code update_plan}, and the context pipeline recalls it into
   * every subsequent turn unconditionally.
   *
   * <p>Alongside the plan, this agent also grants the {@link
   * org.jwcarman.nessy.spi.notebook.NotebookTools#remember(org.jwcarman.nessy.spi.notebook.Notebook,
   * java.util.function.Function) remember}, {@link
   * org.jwcarman.nessy.spi.notebook.NotebookTools#recall(org.jwcarman.nessy.spi.notebook.Notebook,
   * java.util.function.Function) recall}, and {@link
   * org.jwcarman.nessy.spi.notebook.NotebookTools#forget(org.jwcarman.nessy.spi.notebook.Notebook,
   * java.util.function.Function) forget} tools over a single, process-lifetime {@link
   * org.jwcarman.nessy.spi.notebook.Notebook} (spec §6): a fixed subject resolver maps every
   * conversation this process ever holds to the same {@link
   * org.jwcarman.nessy.api.conversation.SubjectId}, so notes made in one chat-cli conversation are
   * remembered in the next — within this run only, since the notebook is in-memory; a {@code
   * JdbcNotebook} swap is the only change needed to survive a restart.
   *
   * <p>Returns the {@link PlanStore} alongside the agent (rather than the agent alone) so {@code
   * Chat}'s {@code main} can hand the same store to {@code ConsoleRepl.Builder#plan(PlanStore)} —
   * the grant principle applied to the console's own opt-in: the store the model writes through
   * {@code update_plan} is the exact store the REPL reads back to render the checklist.
   */
  public static Built agentFor(ModelProvider provider, String model) {
    PlanStore planStore = PlanStore.inMemory();
    Notebook notebook = Notebook.inMemory();
    Function<ConversationId, SubjectId> subjectResolver = id -> new SubjectId("chat-cli-user");
    Transcript transcript = Transcript.inMemory();
    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("chat-cli")
            .model(model)
            .systemPrompt(SYSTEM_PROMPT)
            .tools(
                ToolGrant.grant(new AddTool(), UsagePolicy.allow()),
                ToolGrant.grant(new ClockTool(), UsagePolicy.requireApproval()),
                ToolGrant.grant(PlanTools.updatePlan(planStore), UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.remember(notebook, subjectResolver), UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.recall(notebook, subjectResolver), UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.forget(notebook, subjectResolver), UsagePolicy.allow()))
            // Replaces the builder's default in-memory pipeline Memory with one over an
            // explicitly held transcript — same durability class, now with the plan and the
            // notebook index riding recall (spec §6). The notebook transformer is registered
            // after the plan transformer: enrich appends at the tail, so the notebook index
            // ends up closer to the model's next turn than the plan checklist does.
            .memory(
                Memory.pipeline(transcript)
                    .transform(PlanTools.transformer(planStore))
                    .transform(NotebookTools.transformer(notebook, subjectResolver))
                    .build())
            .approver(new ConsoleApprover())
            .listen(ConversationEvent.ModelResponded.class, DemoAgent::announceUsage)
            .build();
    return new Built(agent, planStore);
  }

  /**
   * The agent {@link #agentFor} builds, paired with the {@link PlanStore} it writes its plan into.
   */
  record Built(Agent<String> agent, PlanStore planStore) {}

  /** The fact-log side of the story: printed independently of whatever the renderer narrates. */
  private static void announceUsage(ConversationEvent.ModelResponded responded) {
    var usage = responded.usage();
    IO.println(
        "\ntokens: "
            + usage.inputTokens()
            + " in / "
            + usage.outputTokens()
            + " out ("
            + usage.cachedInputTokens()
            + " cached)");
  }

  /** Arithmetic, ungated: a tool the model can use freely. */
  record Add(int left, int right) {}

  static final class AddTool implements Tool<Add> {

    @Override
    public String name() {
      return "add";
    }

    @Override
    public String description() {
      return "Adds two integers and returns the sum.";
    }

    @Override
    public Class<Add> inputType() {
      return Add.class;
    }

    @Override
    public String describe(Add input) {
      return input.left() + " + " + input.right();
    }

    @Override
    public Awaited<ToolResult> execute(Add input, ToolContext context) {
      return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
  }

  /**
   * No side effects, no arguments — gated anyway, so every demo run exercises the approval gate.
   */
  record Now() {}

  static final class ClockTool implements Tool<Now> {

    @Override
    public String name() {
      return "clock";
    }

    @Override
    public String description() {
      return "Returns the current date and time.";
    }

    @Override
    public Class<Now> inputType() {
      return Now.class;
    }

    @Override
    public String describe(Now input) {
      return "read the current time";
    }

    @Override
    public Awaited<ToolResult> execute(Now input, ToolContext context) {
      // Explicit zone (S8688): the demo reports the machine's own local time, so
      // ZoneId.systemDefault() names the zone the implicit no-arg now() was silently assuming.
      return Awaited.ready(ToolResult.ok(ZonedDateTime.now(ZoneId.systemDefault()).toString()));
    }
  }
}
