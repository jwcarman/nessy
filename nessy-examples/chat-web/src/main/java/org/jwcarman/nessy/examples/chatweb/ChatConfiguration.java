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
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.ContextConfig;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.backend.jdbc.JdbcRowLocks;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.engine.chapter.ProseSummarizer;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The chat agent: a notebook, a plan, a date tool, and an email tool a person has to approve.
 *
 * <p>The starter supplies the factory, the provider (from {@code nessy.provider}) and the model
 * (from {@code nessy.model}); this class declares the harness itself because the starter's free one
 * binds tool beans with defaults, and an email needs an approver.
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

  /**
   * One turn at a time per agent, and a database transaction is what says so.
   *
   * <p>Without this the direct door falls back to locks held in this process, which are scoped to
   * this one JVM, so a second instance would not see them at all. {@link Locks#TURN} is the
   * transaction boundary the direct door builds each step around ({@code JdbcRowLocks.withLock} IS
   * the transaction), so it must be exact rather than believed -- a lease would leave that boundary
   * with no transaction at all, and the several appends {@code recoverToIdle} makes would stop
   * being atomic. That is a lock, not a lease.
   */
  @Bean
  public Locks agentLocks(DataSource dataSource, PlatformTransactionManager transactions) {
    return new JdbcRowLocks(dataSource, transactions);
  }

  @Bean
  public Plans plans(DataSource dataSource, CodecFactory codecs) {
    return new JdbcPlans(dataSource, TYPE, codecs);
  }

  /**
   * A web assistant on the direct door.
   *
   * <p>Somebody is in the browser waiting, which is what this door is for. The turn runs on the
   * request thread and the answer is the return value; the SSE stream carries what is happening
   * while it runs, which is narration rather than delivery.
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
  public DirectHarness<String, String> harness(
      DefaultDirectHarnessFactory factory,
      NessyProperties properties,
      Map<String, InferenceProvider> providers,
      ObservationRegistry observations,
      SendEmailTool email,
      Approver desk,
      Notebook notebook,
      Plans plans,
      @Value("${chat.chapter-turns:20}") int chapterTurns,
      @Value("${chat.summary-model:}") String summaryModel) {
    return factory.<String>create(
        TYPE,
        config ->
            config
                .inputRenderer(said -> List.of(new Block.Text(said)))
                .systemPrompt(properties.resolveSystemPrompt())
                // Two sources of background: the notebook's index and the current plan. Both
                // ambient, so they are asked afresh every call and never written to the story --
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
                                            factory,
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
  /**
   * How long a turn will hold a request thread waiting for a person.
   *
   * <p>Generous, because somebody reading a message before sending it is not being slow. Bounded,
   * because a thread waiting forever on a closed tab is one nobody gets back -- and an unanswered
   * question is a no, which is the direction a gate should fail in.
   */
  private static final Duration PATIENCE = Duration.ofMinutes(5);

  /**
   * Asks the page, and waits.
   *
   * <p>On the queued door this wrote the question down and returned {@code deferred}, and the
   * engine came back for the answer whenever it arrived. Here the turn is on a request thread, so
   * the answer has to reach it there: the card goes out on the desk's own stream and this blocks
   * until somebody clicks or the patience runs out.
   */
  @Bean
  public Approver desk(ApprovalDesk desk, ApprovalStreams streams) {
    return request -> {
      desk.expecting(request);
      desk.card(request.callId()).ifPresent(card -> streams.asked(request.agentId(), card));
      return Awaited.ready(desk.await(request.callId(), PATIENCE));
    };
  }

  /** The agent's own model writes the summaries, unless {@code summaryModel} names another. */
  private static ContextConfig summaries(
      ContextConfig ctx,
      DefaultDirectHarnessFactory factory,
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
            factory.histories(),
            ObservedInferenceProvider.wrap(provider, observations),
            new InferenceOptions(summaryModel, properties.maxTokens())));
  }
}
