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
package org.jwcarman.nessy.examples.chatweb;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.ContextConfig;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.engine.chapter.ProseSummarizer;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.planning.JdbcPlans;
import org.jwcarman.nessy.planning.PlanTools;
import org.jwcarman.nessy.planning.Plans;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The chat agent: a notebook, a plan, a date tool, and an email tool a person has to approve.
 *
 * <p>The starter supplies the factory, the provider (from {@code nessy.provider}) and the model
 * (from {@code nessy.model}), but no harness: a harness is the agent's definition (its tools, their
 * approvers and its prompt), which only the application knows. This class declares it, and the
 * email tool needs an approver.
 */
@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {

  static final AgentType TYPE = new AgentType("chat");

  @Bean
  public SendEmailTool sendEmailTool() {
    return new SendEmailTool();
  }

  @Bean
  public Notebook notebook(DataSource dataSource, CodecFactory codecs) {
    return new JdbcNotebook(dataSource, TYPE, codecs);
  }

  @Bean
  public Plans plans(DataSource dataSource, CodecFactory codecs) {
    return new JdbcPlans(dataSource, TYPE, codecs);
  }

  /**
   * A web assistant on the queued door.
   *
   * <p>Whoever speaks is told that the message was accepted and is not held for the answer: the
   * turn runs on the engine's own threads, and the SSE stream carries what happens while it runs
   * and how it ends. A message told while a turn is in progress waits in the agent's queue and runs
   * after it.
   *
   * <p>The email tool's approver defers and keeps nothing. Nessy holds the approval request open
   * for {@code chat.approval-term}, and the page reads what is waiting from Nessy, so a request
   * outlives the tab and the process that asked.
   *
   * <p>A chapter of the conversation closes every {@code chat.chapter-turns} turns and is
   * summarised off the request thread. The default is the engine's own twenty; a small number
   * closes one after a few messages, which is how to watch a chapter being cut.
   *
   * <p>A chapter is summarised by the agent's own model unless {@code chat.summary-model} names
   * another, on the same provider. Locally that lets one model answer and a second, better at
   * prose, write the summaries.
   */
  @Bean
  public QueuedHarness<String> harness(
      QueuedHarnessFactory factory,
      TurnHistories histories,
      NessyProperties properties,
      Map<String, InferenceProvider> providers,
      ObservationRegistry observations,
      SendEmailTool email,
      Notebook notebook,
      Plans plans,
      @Value("${chat.chapter-turns:20}") int chapterTurns,
      @Value("${chat.summary-model:}") String summaryModel,
      @Value("${chat.approval-term:PT5M}") Duration approvalTerm) {
    Approver deferring = _ -> Awaited.deferred();
    return factory.create(
        TYPE,
        config ->
            config
                .inputRenderer(said -> List.of(new Block.Text(said)))
                .backlogPolicy(together())
                .systemPrompt(properties.resolveSystemPrompt())
                // Two sources of background: the notebook's index and the current plan. Both
                // ambient, so they are asked afresh every call and never part of the story --
                // the model sees them as they stand NOW.
                .inference(
                    in ->
                        in.model(properties.model())
                            .maxTokens(properties.maxTokens())
                            .context(
                                ctx ->
                                    // A local thinking model can take minutes to write a chapter's
                                    // summary, so the lease outlasts the two-minute default.
                                    summaries(
                                            ctx.chapterLeaseTtl(Duration.ofMinutes(10))
                                                .chapterPolicy(ChapterPolicy.every(chapterTurns)),
                                            histories,
                                            properties,
                                            providers,
                                            observations,
                                            summaryModel)
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
                            // How long a person has to answer. An answer after it is ignored and
                            // the call is recorded as failed.
                            .approver(deferring, approval -> approval.timeout(approvalTerm))
                            // The body too: a page has room, and approving a message you have not
                            // read is not approval. The console example trims for want of screen;
                            // here there is none of that excuse.
                            .action(
                                input ->
                                    "Send an email to %s, subject \"%s\": %s"
                                        .formatted(input.to(), input.subject(), input.body()))));
  }

  /**
   * Messages that arrive while the agent is busy are given to it together.
   *
   * <p>One constant key makes everything waiting a single input, joined with a blank line, the
   * earlier message first. A person who sends three lines in a row means one thing, and one turn
   * answers it better than three. A message sent to an idle agent starts its turn at once and is
   * never merged.
   */
  static BacklogPolicy<String> together() {
    return BacklogPolicy.mergeBy(said -> "chat", (earlier, later) -> earlier + "\n\n" + later);
  }

  /** The agent's own model writes the summaries, unless {@code summaryModel} names another. */
  private static ContextConfig summaries(
      ContextConfig ctx,
      TurnHistories histories,
      NessyProperties properties,
      Map<String, InferenceProvider> providers,
      ObservationRegistry observations,
      String summaryModel) {
    if (summaryModel.isBlank()) {
      return ctx;
    }
    InferenceProvider provider = providers.get(properties.provider());
    if (provider == null) {
      throw new IllegalStateException(
          "chat.summary-model is set, but no provider named '" + properties.provider() + "' is");
    }
    return ctx.summarizer(
        new ProseSummarizer(
            histories,
            ObservedInferenceProvider.wrap(provider, observations),
            new InferenceOptions(summaryModel, properties.maxTokens())));
  }
}
