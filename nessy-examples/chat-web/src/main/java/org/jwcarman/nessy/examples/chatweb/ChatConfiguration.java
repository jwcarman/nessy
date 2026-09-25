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
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.lease.JdbcLeases;
import org.jwcarman.nessy.memory.episodic.EpisodeSummarizer;
import org.jwcarman.nessy.memory.episodic.EpisodeTools;
import org.jwcarman.nessy.memory.episodic.JdbcEpisodes;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.planning.JdbcPlanStore;
import org.jwcarman.nessy.planning.PlanStore;
import org.jwcarman.nessy.planning.PlanTools;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The chat agent: a notebook, a plan, episodes, a date tool, and an email tool a person has to
 * approve.
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

  /**
   * The store mints its own embedder, because the model the vectors were written with is a fact of
   * the store rather than of the application. This one takes the factory's default, which is what
   * {@code nessy.embedding.openai.model} names; with no embedding module on the classpath there is
   * no factory, and the store ranks by recency instead.
   */
  @Bean
  public JdbcEpisodes episodes(DataSource dataSource, ObjectProvider<EmbedderFactory> embedders) {
    Embedder embedder =
        embedders.getIfAvailable() == null ? null : embedders.getObject().create(c -> {});
    return JdbcEpisodes.create(c -> c.dataSource(dataSource).agentType(TYPE).embedder(embedder));
  }

  /**
   * Summarises each episode in the background, under a lease, once the model has begun the next.
   * The model then sees the summaries of the episodes that bear on the current turn and the turns
   * of the current episode, up to {@value #MAX_TAIL} of them. The lease is generous because a local
   * thinking model can take minutes over a long episode; a hosted one takes seconds.
   */
  @Bean
  public EpisodeSummarizer episodeSummarizer(
      TurnHistories histories,
      JdbcEpisodes episodes,
      DataSource dataSource,
      InferenceProvider provider,
      NessyProperties properties,
      ObjectProvider<ObservationRegistry> observations) {
    return EpisodeSummarizer.create(
        c ->
            c.agentType(TYPE)
                .episodes(episodes)
                .histories(histories)
                // Its own kind and its own generous lease: a local thinking model can
                // take minutes over a long episode, and erring long only delays the
                // next attempt, where erring short lets two summarise at once.
                .locks(new JdbcLeases(dataSource, "episode", Duration.ofMinutes(10)))
                .inference(
                    provider, new InferenceOptions(properties.model(), properties.maxTokens()))
                // Each summary is a nessy.summary span with its model call inside, when the
                // application is tracing.
                .observations(observations.getIfAvailable(() -> ObservationRegistry.NOOP)));
  }

  /**
   * A web assistant on the direct door.
   *
   * <p>Somebody is in the browser waiting, which is what this door is for. The turn runs on the
   * request thread and the answer is the return value; the SSE stream carries what is happening
   * while it runs, which is narration rather than delivery.
   */
  @Bean
  public DirectHarness<String> harness(
      DirectHarnessFactory factory,
      NessyProperties properties,
      SendEmailTool email,
      Approver desk,
      Notebook notebook,
      PlanStore plans,
      JdbcEpisodes episodes,
      EpisodeSummarizer summarizer) {
    return factory.<String>create(
        config ->
            config
                .agentType(TYPE)
                .inputRenderer(said -> List.of(new Block.Text(said)))
                .systemPrompt(properties.resolveSystemPrompt())
                // Hears every turn end and summarises any episode that has closed -- on its own
                // thread, because a summary is a model call, and under a lease, so several
                // instances never summarise one agent twice.
                .listener(summarizer.listener())
                // Three sources of background: the notebook's index, the current plan and the
                // episode index. All ambient, so they are asked afresh every call and never
                // written to the story -- the model sees them as they stand NOW.
                .inference(
                    in ->
                        in.model(properties.model())
                            .maxTokens(properties.maxTokens())
                            .context(
                                ctx ->
                                    ctx.summaries(episodes)
                                        .maxTail(MAX_TAIL)
                                        .ambient(NotebookTools.index(notebook))
                                        .ambient(PlanTools.plan(plans))
                                        .ambient(EpisodeTools.index(episodes))))
                .tool(new DaysUntilTool())
                .tool(EpisodeTools.begin(episodes))
                .tool(EpisodeTools.recall(episodes))
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
}
