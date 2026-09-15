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
import org.jwcarman.nessy.planning.JdbcPlanStore;
import org.jwcarman.nessy.planning.PlanStore;
import org.jwcarman.nessy.planning.PlanTools;
import org.jwcarman.nessy.prompt.PromptVariableSource;
import org.jwcarman.nessy.prompt.TemplatedSystemPrompt;
import org.jwcarman.nessy.prompt.spring.SpringPromptTemplateFactory;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A terminal chat with a notebook, a plan, and one tool a person has to approve. */
public final class Chat {

  private static final AgentType TYPE = new AgentType("chat");

  /** A template: {@code ${today}} is filled in on every call, so the date is never stale. */
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

  private Chat() {}

  public static void main(String[] args) {
    Clock clock = Clock.systemDefaultZone();
    // Built here rather than left to the REPL, because the notebook and the plan are opened over
    // it: what the agent remembers and what its tools read have to be one database. PostgreSQL,
    // because the engine's schema is PostgreSQL's -- the same variables Boot reads, so one set of
    // settings serves both. Initialized because it is OURS to initialize.
    DataSource database = fromEnvironment();
    Schemas.initialize(database);
    Notebook notebook = new JdbcNotebook(database, TYPE);
    PlanStore plans = new JdbcPlanStore(database, TYPE);
    Repl.run(
        config ->
            config
                // No exitOn: the defaults already take exit, quit, /exit and /quit, in any case.
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
                .dataSource(database)
                // Two sources of background: the notebook's index and the current plan. Both are
                // ambient, so they are asked afresh every call and never written to the story --
                // the model sees the notes and the plan as they stand NOW.
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
                            // Recipient, subject AND the body -- consenting to a message you have
                            // not read is not consent. Trimmed rather than omitted.
                            .action(
                                input ->
                                    "Send an email to %s%n    subject: %s%n    body: %s"
                                        .formatted(
                                            input.to(), input.subject(), trimmed(input.body())))));
  }

  private static DataSource fromEnvironment() {
    String url = System.getenv("SPRING_DATASOURCE_URL");
    if (url == null || url.isBlank()) {
      throw new IllegalStateException(
          "set SPRING_DATASOURCE_URL (and SPRING_DATASOURCE_USERNAME/PASSWORD) to a PostgreSQL"
              + " database; the engine keeps its agents there");
    }
    DriverManagerDataSource database =
        new DriverManagerDataSource(
            url,
            System.getenv("SPRING_DATASOURCE_USERNAME"),
            System.getenv("SPRING_DATASOURCE_PASSWORD"));
    // Named rather than discovered: under exec:java the driver sits in a class loader that
    // DriverManager's own lookup never sees, and "no suitable driver" is all it would say.
    database.setDriverClassName("org.postgresql.Driver");
    return database;
  }
}
