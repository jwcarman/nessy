package org.jwcarman.nessy.examples.chatweb;

import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.memory.plan.JdbcPlanStore;
import org.jwcarman.nessy.memory.plan.PlanStore;
import org.jwcarman.nessy.memory.plan.PlanTools;
import org.jwcarman.nessy.memory.summarizing.HeadSummarizer;
import org.jwcarman.nessy.memory.summarizing.JdbcSummaries;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The chat agent: a notebook, a plan, a date tool, and an email tool a person has to approve.
 *
 * <p>The starter supplies the factory, the provider (from {@code openai.*}) and the model (from
 * {@code nessy.model}); this class declares the harness itself because the starter's free one binds
 * tool beans with defaults, and an email needs an approver.
 */
@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {

  static final AgentType TYPE = new AgentType("chat");

  @Bean
  public SendEmailTool sendEmailTool() {
    return new SendEmailTool();
  }

  @Bean
  public Notebook notebook(DataSource dataSource) {
    return new JdbcNotebook(dataSource, TYPE);
  }

  @Bean
  public PlanStore planStore(DataSource dataSource) {
    return new JdbcPlanStore(dataSource, TYPE);
  }

  private static final int MAX_TAIL = 20;
  private static final int MIN_TAIL = 8;

  @Bean
  public JdbcSummaries summaries(DataSource dataSource) {
    return new JdbcSummaries(dataSource, TYPE);
  }

  /**
   * Summarises the head of a long conversation in the background, under a lease, on the starter's
   * sweep. The model then sees the summaries and the last {@value #MAX_TAIL} turns; once more than
   * that many follow the last summary, the oldest are summarised down to {@value #MIN_TAIL}.
   */
  @Bean
  public HeadSummarizer headSummarizer(
      DefaultHarnessFactory factory,
      JdbcSummaries summaries,
      InferenceProvider provider,
      NessyProperties properties) {
    return HeadSummarizer.create(
        c ->
            c.agentType(TYPE)
                .summaries(summaries)
                .histories(factory.histories())
                .leases(factory.leases())
                .inference(
                    provider, new InferenceOptions(properties.model(), properties.maxTokens()))
                .tail(MAX_TAIL, MIN_TAIL));
  }

  @Bean
  public Harness<String> harness(
      DefaultHarnessFactory factory,
      NessyProperties properties,
      SendEmailTool email,
      Approver desk,
      Notebook notebook,
      PlanStore plans,
      JdbcSummaries summaries) {
    return factory.create(
        String.class,
        config ->
            config
                .agentType(TYPE)
                .systemPrompt(properties.resolveSystemPrompt())
                // Two sources of background: the notebook's index and the current plan. Both are
                // ambient, so they are asked afresh every call and never written to the story --
                // the model sees the notes and the plan as they stand NOW.
                .inference(
                    in ->
                        in.context(
                            ctx ->
                                ctx.summaries(summaries)
                                    .maxTail(MAX_TAIL)
                                    .ambient(NotebookTools.index(notebook))
                                    .ambient(PlanTools.plan(plans))))
                .tool(new DaysUntilTool())
                .tool(NotebookTools.remember(notebook))
                .tool(NotebookTools.revise(notebook))
                .tool(NotebookTools.recall(notebook))
                .tool(NotebookTools.forget(notebook))
                .tool(PlanTools.updatePlan(plans))
                .tool(
                    email,
                    binding ->
                        binding
                            .approver(desk)
                            // The body too: a page has room, and approving a message you have not
                            // read is not approval. The console example trims for want of screen;
                            // here there is none of that excuse.
                            .action(
                                input ->
                                    "Send an email to %s, subject \"%s\": %s"
                                        .formatted(input.to(), input.subject(), input.body()))));
  }

  /**
   * Hands the question to the desk and tells the page, on the desk's own stream beside the one the
   * engine narrates on. The engine narrates that an approval was sought BEFORE it asks the
   * approver, so the page cannot be told from that event -- it would find a desk that has not heard
   * yet. Told here, the card is complete when it arrives, and journaled, so a page opened later
   * still sees it.
   */
  @Bean
  public Approver desk(ApprovalDesk desk, ApprovalStreams streams) {
    return request -> {
      desk.expecting(request);
      desk.card(request.callId()).ifPresent(card -> streams.asked(request.agentId(), card));
      return Awaited.deferred();
    };
  }
}
