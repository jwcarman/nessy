package org.jwcarman.nessy.examples.chatweb;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.embedding.Embedder;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbedder;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.lease.Leases;
import org.jwcarman.nessy.memory.episodic.EpisodeSummarizer;
import org.jwcarman.nessy.memory.episodic.EpisodeTools;
import org.jwcarman.nessy.memory.episodic.JdbcEpisodes;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.planning.JdbcPlanStore;
import org.jwcarman.nessy.planning.PlanStore;
import org.jwcarman.nessy.planning.PlanTools;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
   * What ranks episodes by relevance: an embedding model at the same OpenAI-compatible endpoint the
   * chat model is at, when {@code CHAT_EMBEDDING_MODEL} names one. Without it the store shows the
   * most recent episodes instead, so the example runs on a chat model alone.
   */
  @Bean
  @ConditionalOnProperty("chat.embedding-model")
  public Embedder embedder(
      @Value("${chat.embedding-model}") String model,
      @Value("${openai.base-url}") String baseUrl,
      @Value("${openai.api-key}") String apiKey) {
    return OpenAiEmbedder.create(c -> c.apiKey(apiKey).baseUrl(baseUrl).model(model));
  }

  @Bean
  public JdbcEpisodes episodes(DataSource dataSource, ObjectProvider<Embedder> embedder) {
    return JdbcEpisodes.create(
        c -> c.dataSource(dataSource).agentType(TYPE).embedder(embedder.getIfAvailable()));
  }

  /**
   * Summarises each episode in the background, under a lease, once the model has begun the next.
   * The model then sees the summaries of the episodes that bear on the current turn and the turns
   * of the current episode, up to {@value #MAX_TAIL} of them. The lease is generous because a local
   * thinking model can take minutes over a long episode; a hosted one takes seconds.
   */
  @Bean
  public EpisodeSummarizer episodeSummarizer(
      DefaultHarnessFactory factory,
      JdbcEpisodes episodes,
      Leases leases,
      InferenceProvider provider,
      NessyProperties properties,
      ObjectProvider<ObservationRegistry> observations) {
    return EpisodeSummarizer.create(
        c ->
            c.agentType(TYPE)
                .episodes(episodes)
                .histories(factory.histories())
                .leases(leases)
                .inference(
                    provider, new InferenceOptions(properties.model(), properties.maxTokens()))
                .leaseTtl(Duration.ofMinutes(5))
                // Each summary is a nessy.summary span with its model call inside, when the
                // application is tracing.
                .observations(
                    observations.getIfAvailable(() -> ObservationRegistry.NOOP),
                    properties.provider()));
  }

  @Bean
  public Harness<String> harness(
      DefaultHarnessFactory factory,
      NessyProperties properties,
      SendEmailTool email,
      Approver desk,
      Notebook notebook,
      PlanStore plans,
      JdbcEpisodes episodes,
      EpisodeSummarizer summarizer) {
    return factory.create(
        String.class,
        config ->
            config
                .agentType(TYPE)
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
                        in.context(
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
  @Bean
  public Approver desk(ApprovalDesk desk, ApprovalStreams streams) {
    return request -> {
      desk.expecting(request);
      desk.card(request.callId()).ifPresent(card -> streams.asked(request.agentId(), card));
      return Awaited.deferred();
    };
  }
}
