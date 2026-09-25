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
import org.jwcarman.nessy.engine.direct.DirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.planning.JdbcPlanStore;
import org.jwcarman.nessy.planning.PlanStore;
import org.jwcarman.nessy.planning.PlanTools;
import org.jwcarman.nessy.prompt.PromptVariableSource;
import org.jwcarman.nessy.prompt.TemplatedSystemPrompt;
import org.jwcarman.nessy.prompt.spring.SpringPromptTemplateFactory;
import org.jwcarman.nessy.spi.store.Schemas;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.jwcarman.nessy.spring.boot.prompt.PromptAutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * A terminal chat with a notebook, a plan, and one tool a person has to approve.
 *
 * <p>An ordinary Spring Boot application, which is what it always was underneath: the provider is a
 * bean its own module contributes, and the database comes up from {@code compose.yaml} when this
 * runs and goes away after. There is nothing to start first and no connection details to export.
 *
 * <p><b>The conversation is not in that database.</b> A terminal's chat lives as long as the
 * terminal does. What the notebook and the plan keep is the part worth outliving it.
 */
// Nessy's own auto-configuration builds the QUEUED world -- a harness factory over a database,
// with a model and a system prompt read from properties. This application builds a direct harness
// itself and says what it is for in code, so that configuration has nothing to do here.
@SpringBootApplication(exclude = {NessyAutoConfiguration.class, PromptAutoConfiguration.class})
public class Chat {

  private static final AgentType TYPE = new AgentType("chat");

  private static final String SYSTEM_PROMPT =
      """
      You are a concise, friendly assistant living in someone's terminal. Keep answers short \
      unless asked for more.
      Today is ${today}. When a question turns on counting days, use the days_until tool rather \
      than working it out yourself -- and never assume the year.
        When you are told something worth keeping -- a preference, a name, a standing fact -- \
        remember it as a note. Your notes appear as an index every time; read one in full with \
        the recall tool when it is relevant, and change one with revise using the id from that \
        index. Never invent an id.
        For work that takes several steps, write a plan with the update_plan tool and keep it \
      current as you go. The plan you are holding appears in every message.""";

  private static final int SHOWN_BODY_CHARACTERS = 240;

  private static String trimmed(String body) {
    if (body == null || body.isBlank()) {
      return "(empty)";
    }
    String flattened = body.strip().replaceAll("\\s+", " ");
    return flattened.length() <= SHOWN_BODY_CHARACTERS
        ? flattened
        : flattened.substring(0, SHOWN_BODY_CHARACTERS) + "... (" + body.length() + " chars)";
  }

  public static void main(String[] args) {
    // A terminal, not a server: nothing here listens, and the process ends when the chat does.
    new SpringApplicationBuilder(Chat.class)
        .web(org.springframework.boot.WebApplicationType.NONE)
        .run(args);
  }

  @Bean
  public Notebook notebook(DataSource database) {
    Schemas.initialize(database);
    return new JdbcNotebook(database, TYPE);
  }

  @Bean
  public PlanStore plans(DataSource database) {
    return new JdbcPlanStore(database, TYPE);
  }

  /** Everything an application owns once. In memory, because the conversation is the process. */
  @Bean
  public DirectHarnessFactory harnesses(InferenceProvider provider) {
    return DirectHarnessFactory.inMemory(
        provider, new VictoolsInputSchemaGenerator(), JsonMapper.builder().build());
  }

  @Bean
  public CommandLineRunner terminal(
      DirectHarnessFactory harnesses,
      @Value("${nessy.model}") String model,
      Notebook notebook,
      PlanStore plans,
      Clock clock) {
    return _ ->
        Repl.run(
            harnesses,
            model,
            config ->
                config
                    // No exitOn: the defaults already take exit, quit, /exit and /quit.
                    .banner("nessy chat -- type /exit or press Ctrl-D to leave")
                    .prompt("> ")
                    .farewell("bye.")
                    .systemPrompt(
                        TemplatedSystemPrompt.of(
                            new SpringPromptTemplateFactory(),
                            SYSTEM_PROMPT,
                            PromptVariableSource.supplied(
                                "today", () -> LocalDate.now(clock).toString())))
                    .agent(TYPE)
                    // Two sources of background: the notebook's index and the current plan. Both
                    // are ambient, so they are asked afresh every call and never written to the
                    // story -- the model sees the notes and the plan as they stand NOW.
                    .harness(
                        h ->
                            h.inference(
                                in ->
                                    in.context(
                                        ctx ->
                                            ctx.ambient(NotebookTools.index(notebook))
                                                .ambient(PlanTools.plan(plans)))))
                    .tool(new DaysUntilTool())
                    .tool(NotebookTools.remember(notebook))
                    .tool(NotebookTools.revise(notebook))
                    .tool(NotebookTools.recall(notebook))
                    .tool(NotebookTools.forget(notebook))
                    .tool(PlanTools.updatePlan(plans))
                    // The only thing here that reaches outside the process, so the only thing a
                    // person is asked about. The renderer writes the sentence they consent to.
                    .tool(
                        new SendEmailTool(),
                        binding ->
                            binding
                                .approver(ConsoleApprover.atTheTerminal())
                                // Recipient, subject AND the body -- consenting to a message you
                                // have not read is not consent. Trimmed rather than omitted.
                                .action(
                                    input ->
                                        "Send an email to %s%n    subject: %s%n    body: %s"
                                            .formatted(
                                                input.to(),
                                                input.subject(),
                                                trimmed(input.body())))));
  }

  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
