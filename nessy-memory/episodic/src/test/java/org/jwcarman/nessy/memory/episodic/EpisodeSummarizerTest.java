package org.jwcarman.nessy.memory.episodic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.engine.inference.ObservedInference;
import org.jwcarman.nessy.lease.JdbcLeases;
import org.jwcarman.nessy.memory.summarizing.SummaryObservation;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.store.Schemas;

/**
 * A whole engine on PostgreSQL, with a model that answers chat and writes episode summaries, so the
 * sweep is proven against the story the engine keeps and the context it assembles -- ranked against
 * the turn being answered.
 */
@DisplayName("The episode summariser")
class EpisodeSummarizerTest {

  private static final int MAX_TAIL = 20;

  private final List<InferenceRequest> chatRequests = new CopyOnWriteArrayList<>();
  private final List<InferenceRequest> summaryRequests = new CopyOnWriteArrayList<>();
  private final AtomicBoolean refuse = new AtomicBoolean();
  private final KeywordEmbedder embedder = new KeywordEmbedder("kw", "cats", "dogs");

  /**
   * Answers chat in one word; summarises with the words the episode used, so the embedder sees
   * them.
   */
  private final InferenceProvider model =
      (request, narrator) -> {
        if (request.systemPrompt().value().equals(EpisodeSummarizer.PROMPT)) {
          summaryRequests.add(request);
          if (refuse.get()) {
            return new InferenceResult.Refusal("not today");
          }
          String subject =
              request.context().turns().stream()
                  .filter(Turn::complete)
                  .map(turn -> ((Block.Text) turn.observation().blocks().getFirst()).text())
                  .reduce("", (a, b) -> a + " " + b);
          return new InferenceResult.Answer(
              List.of(new Block.Text("Title: **About" + subject + "**\n\nSUMMARY:" + subject)));
        }
        chatRequests.add(request);
        return new InferenceResult.Answer(List.of(new Block.Text("ok")));
      };

  private final Recorded recorded = new Recorded();
  private final ObservationRegistry observations = ObservationRegistry.create();

  private HikariDataSource dataSource;
  private DefaultHarnessFactory factory;
  private Harness<String> harness;
  private JdbcEpisodes episodes;
  private EpisodeSummarizer summarizer;

  @BeforeEach
  void start() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(Calls.POSTGRES.getJdbcUrl());
    config.setUsername(Calls.POSTGRES.getUsername());
    config.setPassword(Calls.POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Schemas.initialize(dataSource);
    observations.observationConfig().observationHandler(recorded);
    factory =
        new DefaultHarnessFactory(
            engine -> engine.dataSource(dataSource).inference(model, InferenceOptions.of("m")));
    episodes =
        JdbcEpisodes.create(
            c -> c.dataSource(dataSource).agentType(Calls.TYPE).embedder(embedder).shown(2));
    summarizer =
        EpisodeSummarizer.create(
            c ->
                c.agentType(Calls.TYPE)
                    .episodes(episodes)
                    .histories(factory.histories())
                    .leases(new JdbcLeases(dataSource))
                    .inference(model, InferenceOptions.of("m"))
                    .observations(observations, "test"));
    harness =
        factory.create(
            String.class,
            h ->
                h.agentType(Calls.TYPE)
                    .systemPrompt("You are a test assistant.")
                    .inference(
                        in ->
                            in.context(
                                ctx ->
                                    ctx.summaries(episodes)
                                        .ambient(EpisodeTools.index(episodes))
                                        .maxTail(MAX_TAIL)))
                    .effects(e -> e.pollInterval(Duration.ofMillis(100)))
                    .listener(summarizer.listener()));
  }

  @AfterEach
  void stop() {
    factory.close();
    dataSource.close();
  }

  private void say(AgentId agentId, String... texts) {
    for (String text : texts) {
      int expected = chatRequests.size() + 1;
      harness.observe(agentId, text);
      await().atMost(Duration.ofSeconds(20)).until(() -> chatRequests.size() >= expected);
    }
  }

  private long nextTurn(AgentId agentId) {
    return factory.histories().forAgent(Calls.TYPE, agentId).turnsFrom(0).stream()
            .mapToLong(turn -> turn.id().value())
            .max()
            .orElse(0)
        + 1;
  }

  private static List<Long> turnIds(InferenceRequest request) {
    return request.context().turns().stream().map(turn -> turn.id().value()).toList();
  }

  @Test
  @DisplayName("summarises an episode once it closes, and the next call is built on it")
  void a_closed_episode_is_summarised_and_shown() {
    AgentId agent = Calls.agent();
    episodes.begin(agent, new TurnId(nextTurn(agent)), "cats", "start");
    say(agent, "cats one", "cats two", "cats three");
    long secondBegins = nextTurn(agent);
    episodes.begin(agent, new TurnId(secondBegins), "dogs", "new subject");
    say(agent, "dogs one");

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> episodes.find(agent, 1).map(Episode::summarized).orElse(false));
    Episode first = episodes.find(agent, 1).orElseThrow();
    assertThat(first.summary()).isEqualTo("SUMMARY: cats one cats two cats three");
    assertThat(first.title()).isEqualTo("About cats one cats two cats three");
    assertThat(first.openedAs()).isEqualTo("cats");
    assertThat(first.through()).isEqualTo(new TurnId(secondBegins - 1));
    assertThat(summaryRequests).hasSize(1);
    // Shown the episode's three turns and the ask, nothing before or after.
    assertThat(turnIds(summaryRequests.getFirst())).hasSize(4);
    assertThat(embedder.embedded).contains(first.summary());

    say(agent, "dogs two");
    InferenceRequest last = chatRequests.getLast();
    assertThat(last.context().summaries()).hasSize(1);
    assertThat(last.context().summaries().getFirst().through())
        .isEqualTo(new TurnId(secondBegins - 1));
    assertThat(turnIds(last)).allMatch(id -> id >= secondBegins);
    assertThat(last.context().ambient()).extracting(Ambient::kind).containsExactly("episodes");
    // One summary fits without ranking, so the turn being answered was not embedded.
    assertThat(embedder.embedded).doesNotContain("dogs two");

    // On record: a nessy.summary span that says it wrote, with the model call inside it as a
    // chat span tagged with the provider it was told about.
    assertThat(recorded.names()).contains(SummaryObservation.NAME, ObservedInference.DURATION);
    assertThat(recorded.tag(SummaryObservation.NAME, "nessy.summary.kind")).isEqualTo("episode");
    assertThat(recorded.tag(SummaryObservation.NAME, "nessy.summary.outcome")).isEqualTo("written");
    assertThat(recorded.tag(ObservedInference.DURATION, "gen_ai.provider.name")).isEqualTo("test");
    assertThat(recorded.tag(ObservedInference.DURATION, "gen_ai.response.finish_reasons"))
        .isEqualTo("stop");
  }

  @Test
  @DisplayName("a model that will not summarise leaves the episode for next time")
  void a_refusal_leaves_the_episode_unsummarised() {
    AgentId agent = Calls.agent();
    refuse.set(true);
    episodes.begin(agent, new TurnId(nextTurn(agent)), "cats", "start");
    say(agent, "cats one");
    episodes.begin(agent, new TurnId(nextTurn(agent)), "dogs", "new subject");
    say(agent, "dogs one");

    await().atMost(Duration.ofSeconds(20)).until(() -> !summaryRequests.isEmpty());
    assertThat(episodes.unsummarized(agent)).extracting(Episode::number).containsExactly(1);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> "fault".equals(recorded.tag(SummaryObservation.NAME, "nessy.summary.outcome")));
    assertThat(recorded.tag(ObservedInference.DURATION, "gen_ai.response.finish_reasons"))
        .isEqualTo("content_filter");

    refuse.set(false);
    say(agent, "dogs two");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> episodes.find(agent, 1).map(Episode::summarized).orElse(false));
    // Asked outright, with nothing left to do, nothing happens.
    summarizer.summarizeIfDue(agent);
    assertThat(episodes.unsummarized(agent)).isEmpty();
  }

  @Test
  @DisplayName("an episode nothing was said in is closed with its title")
  void an_empty_episode_is_summarised_as_its_title() {
    AgentId agent = Calls.agent();
    say(agent, "hello");
    long at = nextTurn(agent);
    // Opened at a turn that never happened, then closed before it by the next beginning.
    episodes.begin(agent, new TurnId(at), "a false start", "oops");
    episodes.begin(agent, new TurnId(at + 1), "the real thing", "now");
    say(agent, "cats one");

    // The turn before the first named episode is the opening episode, summarised by the model;
    // the false start itself has no turns and is closed with its title, no model asked.
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> episodes.find(agent, 2).map(Episode::summarized).orElse(false));
    assertThat(episodes.find(agent, 1).map(Episode::openedAs)).contains(JdbcEpisodes.OPENING_TITLE);
    assertThat(episodes.find(agent, 1).map(Episode::title)).contains("About hello");
    assertThat(episodes.find(agent, 1).map(Episode::summary)).contains("SUMMARY: hello");
    assertThat(episodes.find(agent, 2).map(Episode::summary)).contains("a false start");
    assertThat(summaryRequests).hasSize(1);
  }

  @Test
  void a_reply_is_split_into_title_and_summary_when_it_has_both() {
    assertThat(EpisodeSummarizer.Titled.parse("Title: \"Lisbon trip\"\n\nAlfama, October."))
        .isEqualTo(new EpisodeSummarizer.Titled("Lisbon trip", "Alfama, October."));
    assertThat(EpisodeSummarizer.Titled.parse("# Lisbon trip\nAlfama."))
        .isEqualTo(new EpisodeSummarizer.Titled("Lisbon trip", "Alfama."));
    assertThat(EpisodeSummarizer.Titled.parse("Just a summary on one line."))
        .isEqualTo(new EpisodeSummarizer.Titled(null, "Just a summary on one line."));
    assertThat(EpisodeSummarizer.Titled.parse("***\n\nA summary under an empty title."))
        .isEqualTo(new EpisodeSummarizer.Titled(null, "***\n\nA summary under an empty title."));
  }

  @Test
  void what_is_refused_at_configuration() {
    assertThatThrownBy(() -> EpisodeSummarizer.create(c -> c.agentType(Calls.TYPE)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("episodes");
    assertThatThrownBy(
            () ->
                EpisodeSummarizer.create(
                    c -> c.agentType(Calls.TYPE).episodes(episodes).histories(factory.histories())))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("leases");
  }

  /** Every observation stopped, by name, with its low-cardinality tags. */
  static final class Recorded implements ObservationHandler<Observation.Context> {
    final List<Observation.Context> stopped = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStop(Observation.Context context) {
      stopped.add(context);
    }

    List<String> names() {
      return stopped.stream().map(Observation.Context::getName).toList();
    }

    String tag(String name, String key) {
      return stopped.stream()
          .filter(c -> c.getName().equals(name))
          .map(c -> c.getLowCardinalityKeyValue(key))
          .filter(java.util.Objects::nonNull)
          .map(kv -> kv.getValue())
          .reduce((first, second) -> second)
          .orElse(null);
    }
  }
}
