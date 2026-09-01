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
package org.jwcarman.nessy.examples.chatcli;

import java.time.Clock;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.console.ConsoleApprover;
import org.jwcarman.nessy.console.Repl;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.memory.pipeline.MemoryPipeline;
import org.jwcarman.nessy.memory.plan.JdbcPlanStore;
import org.jwcarman.nessy.memory.plan.PlanStore;
import org.jwcarman.nessy.memory.plan.PlanTools;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * A conversation in a terminal, with one tool.
 *
 * <p>This is the whole application. {@code nessy-console} owns everything that used to be here —
 * discovering the model from the environment, forming the actor system's cluster of one, the
 * in-memory substrate and reply tokens, and the loop that streams an answer as it arrives — so what
 * is left is the only part that is actually about THIS program: what it is for, and what it can do.
 *
 * <p>Which model it talks to is not decided here either. Set {@code ANTHROPIC_API_KEY} or {@code
 * GEMINI_API_KEY} or {@code XAI_API_KEY}, or point {@code OPENAI_BASE_URL} at a local runtime; the
 * banner says which one won. See the README.
 */
public final class Chat {

  /** Named here, not left to default, because the notebook and the plan are opened under it. */
  private static final AgentType TYPE = AgentType.of("chat");

  /**
   * How much conversation to carry, as characters rather than tokens.
   *
   * <p>Generous for a terminal: a person types slowly enough that reaching this takes a long
   * sitting, and the alternative — an eternal transcript — eventually sends a model more than it
   * can read.
   */
  private static final int TRANSCRIPT_BUDGET = 100_000;

  /**
   * Says the date outright.
   *
   * <p>A model has a training cutoff and a confident prior about what year it is: asked to help
   * with Christmas shopping it names whichever year it learned about and reasons from that wrong
   * anchor — <em>without</em> calling anything to check. There was a {@code today} tool here and it
   * did not help, for exactly that reason: a tool only works if the model volunteers to use it, and
   * this is the failure where it does not. One line of prompt cannot be skipped.
   *
   * <p>The cost is that this date is fixed when the program starts, so a session running past
   * midnight is a day behind. For a REPL that is a fair trade; an agent that runs for weeks needs a
   * prompt rendered per turn, which is a different thing from a tool.
   */
  private static String systemPrompt(LocalDate today) {
    return """
        You are a concise, friendly assistant living in someone's terminal. Keep answers short \
        unless asked for more.

        Today is %s. When a question turns on counting days, use the days_until tool rather \
        than working it out yourself — and never assume the year.

        When you are told something worth keeping — a preference, a name, a standing fact — \
        remember it as a note. Your notes appear as an index every time; read one in full with \
        the recall tool when it is relevant.

        For work that takes several steps, write a plan with the update_plan tool and keep it \
        current as you go. The plan you are holding appears in every message."""
        .formatted(today);
  }

  private Chat() {}

  public static void main(String[] args) {
    Clock clock = Clock.systemDefaultZone();
    // Built here rather than left to the REPL, because the notebook and the plan are opened over
    // it: what the agent remembers and what its tools read have to be one database, and an
    // application that cannot name it cannot give the agent either. Initialized because it is
    // OURS — Nessy never runs DDL against a database an application supplied uninvited.
    DataSource database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    Schemas.initialize(database);
    Notebook notebook = new JdbcNotebook(database, TYPE);
    PlanStore plans = new JdbcPlanStore(database, TYPE);
    Repl.run(
        config ->
            config
                // No exitOn: the defaults already take exit, quit, /exit and /quit, in any
                // case. Naming a subset here is how "/exit" came to be a message to the model.
                .banner("nessy chat — type /exit or press Ctrl-D to leave")
                .prompt("> ")
                .farewell("bye.")
                .systemPrompt(systemPrompt(LocalDate.now(clock)))
                .agent(TYPE)
                .dataSource(database)
                // The transcript, plus two stages of background: the notebook's index and the
                // current plan. Both are ambient, so they are rebuilt every call and never written
                // to the transcript — the model sees the notes and the plan as they stand NOW, and
                // the record stays a record of what was actually said.
                .memory(
                    MemoryPipeline.of(
                        TranscriptMemory.recent(database, TYPE, TRANSCRIPT_BUDGET),
                        pipeline ->
                            pipeline
                                .stage(NotebookTools.index(notebook))
                                .stage(PlanTools.plan(plans))))
                .tool(new DaysUntilTool())
                .tool(NotebookTools.remember(notebook))
                .tool(NotebookTools.recall(notebook))
                .tool(NotebookTools.forget(notebook))
                .tool(PlanTools.updatePlan(plans))
                // The only thing here that reaches outside the process, so the only thing a
                // person is asked about. The describer writes the sentence they consent to.
                .tool(
                    new SendEmailTool(),
                    binding ->
                        binding
                            .approver(ConsoleApprover.atTheTerminal())
                            .describer(
                                input ->
                                    "Send an email to %s, subject \"%s\""
                                        .formatted(input.to(), input.subject()))));
  }
}
