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
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.jwcarman.nessy.console.ConsoleApprover;
import org.jwcarman.nessy.console.Repl;
import org.jwcarman.nessy.console.ReplConfig;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.planning.JdbcPlans;
import org.jwcarman.nessy.planning.PlanTools;
import org.jwcarman.nessy.planning.Plans;
import org.jwcarman.nessy.spring.boot.prompt.PromptAutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

/**
 * A terminal chat with a notebook, a plan, and one tool a person has to approve.
 *
 * <p>An ordinary Spring Boot application on the Nessy starter: the provider is a bean the starter
 * registers from a preset, and the database comes up from {@code compose.yaml} when this runs and
 * goes away after. There is nothing to start first and no connection details to export.
 *
 * <p><b>The conversation is in that database too.</b> The starter's JDBC backend keeps it beside
 * the notebook and the plan. Every launch starts a new conversation and prints its id; {@code
 * --nessy.console.agent=<id>} resumes one.
 */
// The starter builds the direct-door factory over the JDBC backend and registers every
// InferenceProvider bean by name; nessy.provider and nessy.model say which one answers. The prompt
// auto-configuration is excluded because it insists on nessy.system-prompt, and this application
// states its prompt in code, fixed for the life of the harness; the date, which changes, is an
// ambient source.
@SpringBootApplication(exclude = PromptAutoConfiguration.class)
public class Chat {

  private static final AgentType TYPE = new AgentType("chat");

  private static final String SYSTEM_PROMPT =
      """
      You are a concise, friendly assistant living in someone's terminal. Keep answers short \
      unless asked for more.
      When a question turns on counting days, use the days_until tool rather \
      than working it out yourself -- and never assume the year. The date is given to you with \
      every message.
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
  public Plans plans(DataSource database) {
    return new JdbcPlans(database, TYPE);
  }

  /**
   * The terminal itself. {@code chat.terminal=false} leaves it out, which is how a test starts the
   * whole application without reading a console.
   */
  @Bean
  @ConditionalOnProperty(name = "chat.terminal", havingValue = "true", matchIfMissing = true)
  public CommandLineRunner terminal(
      DirectHarnessFactory harnesses,
      @Value("${nessy.model}") String model,
      @Value("${nessy.console.agent:}") String resume,
      Notebook notebook,
      Plans plans,
      Clock clock) {
    return _ -> {
      Optional<String> problem = problemWith(resume);
      if (problem.isPresent()) {
        System.out.println(problem.get());
        return;
      }
      Repl.run(
          harnesses,
          model,
          config ->
              resuming(config, resume)
                  // No exitOn: the defaults already take exit, quit, /exit and /quit.
                  .banner("nessy chat -- type /exit or press Ctrl-D to leave")
                  .prompt("> ")
                  .farewell("bye.")
                  .systemPrompt(SYSTEM_PROMPT)
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
                                          ctx.ambient(today(clock))
                                              .ambient(NotebookTools.index(notebook))
                                              .ambient(PlanTools.plan(plans)))))
                  .tool(new DaysUntilTool())
                  .tool(NotebookTools.remember(notebook))
                  .tool(NotebookTools.revise(notebook))
                  .tool(NotebookTools.recall(notebook))
                  .tool(NotebookTools.forget(notebook))
                  .tool(PlanTools.updatePlan(plans))
                  // The only thing here that reaches outside the process, so the only thing a
                  // person is asked about. The action stringifier writes the sentence they consent
                  // to.
                  .tool(
                      new SendEmailTool(),
                      binding ->
                          binding
                              .approver(ConsoleApprover.atTheTerminal())
                              // One sentence, since a stringifier's line is one line with its
                              // whitespace collapsed. Recipient, subject AND the body --
                              // consenting to a message you have not read is not consent. The
                              // body is trimmed rather than omitted.
                              .action(
                                  input ->
                                      "Send an email to %s, subject \"%s\", body: %s"
                                          .formatted(
                                              input.to(),
                                              input.subject(),
                                              trimmed(input.body())))));
    };
  }

  /**
   * What is wrong with {@code nessy.console.agent}, said for a person, or nothing if it is fine.
   */
  static Optional<String> problemWith(String resume) {
    try {
      resumed(resume);
      return Optional.empty();
    } catch (IllegalArgumentException notAnId) {
      return Optional.of(
          "nessy.console.agent is not a conversation id (expected a UUID): " + resume);
    }
  }

  /**
   * Resumes the conversation named by {@code nessy.console.agent}; when it is not set the console
   * starts a new one, so the config is returned as it came.
   */
  static ReplConfig resuming(ReplConfig config, String resume) {
    return resumed(resume).map(config::id).orElse(config);
  }

  /** The conversation {@code nessy.console.agent} names, if it names one. */
  static Optional<AgentId> resumed(String resume) {
    if (resume == null || resume.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new AgentId(UUID.fromString(resume.strip())));
  }

  /**
   * The date, asked afresh every call. It is ambient rather than part of the system prompt because
   * a system prompt is fixed for the life of the harness, and a date in it would change the head of
   * every request each day and discard what the provider had cached.
   */
  static AmbientSource today(Clock clock) {
    return AmbientSource.of(
        source ->
            source.kind("clock").text(_ -> Optional.of("Today is " + LocalDate.now(clock) + ".")));
  }

  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
