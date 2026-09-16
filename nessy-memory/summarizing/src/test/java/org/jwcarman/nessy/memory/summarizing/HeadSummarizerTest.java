package org.jwcarman.nessy.memory.summarizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.engine.inference.ObservedInference;
import org.jwcarman.nessy.lease.JdbcLeases;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.store.Schemas;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A whole engine on PostgreSQL, with a model that answers chat and writes summaries, so the sweep
 * is proven against the story the engine actually keeps and the context it actually assembles.
 */
@DisplayName("The head summariser")
class HeadSummarizerTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType CHAT = new AgentType("chat");
  private static final int MAX_TAIL = 6;
  private static final int MIN_TAIL = 2;

  /** Answers chat with the same words; summarises with a line that says how many turns it saw. */
  private final List<InferenceRequest> chatRequests = new CopyOnWriteArrayList<>();

  private final List<InferenceRequest> summaryRequests = new CopyOnWriteArrayList<>();

  private final AtomicInteger summariesWritten = new AtomicInteger();
  private final InferenceProvider model =
      (request, narrator) -> {
        if (request.systemPrompt().value().equals(HeadSummarizer.PROMPT)) {
          summariesWritten.incrementAndGet();
          summaryRequests.add(request);
          // What it was shown: how many turns, and the summary so far if there was one -- so a
          // fold can be told from a fresh start.
          String soFar =
              request.context().summaries().isEmpty()
                  ? ""
                  : " after [" + text(request.context().summaries().getFirst().content()) + "]";
          return new InferenceResult.Answer(
              List.of(
                  new Block.Text(
                      "SUMMARY of "
                          + request.context().turns().stream().filter(Turn::complete).count()
                          + " turns"
                          + soFar)));
        }
        chatRequests.add(request);
        return new InferenceResult.Answer(List.of(new Block.Text("a lake monster")));
      };

  private final Recorded recorded = new Recorded();
  private final ObservationRegistry observations = ObservationRegistry.create();

  private HikariDataSource dataSource;
  private DefaultHarnessFactory factory;
  private Harness<String> harness;
  private JdbcSummaries summaries;
  private HeadSummarizer summarizer;

  @BeforeEach
  void start() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Schemas.initialize(dataSource);
    observations.observationConfig().observationHandler(recorded);
    factory =
        new DefaultHarnessFactory(
            engine -> engine.dataSource(dataSource).inference(model, InferenceOptions.of("m")));
    summaries = new JdbcSummaries(dataSource, CHAT);
    summarizer =
        HeadSummarizer.create(
            c ->
                c.agentType(CHAT)
                    .summaries(summaries)
                    .histories(factory.histories())
                    .leases(new JdbcLeases(dataSource))
                    .inference(model, InferenceOptions.of("m"))
                    .tail(MAX_TAIL, MIN_TAIL)
                    .observations(observations, "test"));
    harness =
        factory.create(
            String.class,
            h ->
                h.agentType(CHAT)
                    .systemPrompt("You are a test assistant.")
                    .inference(in -> in.context(ctx -> ctx.summaries(summaries).maxTail(MAX_TAIL)))
                    .effects(e -> e.pollInterval(Duration.ofMillis(100)))
                    // Hears every turn end; summarises off the narration thread.
                    .listener(summarizer.listener()));
  }

  @AfterEach
  void stop() {
    factory.close();
    dataSource.close();
  }

  private static String text(List<? extends Block> blocks) {
    return ((Block.Text) blocks.getFirst()).text();
  }

  /** The ids of an agent's turns, oldest first: positions in the story, not a count. */
  private List<Long> turnIds(AgentId agentId) {
    return factory.histories().forAgent(CHAT, agentId).turnsFrom(0).stream()
        .map(turn -> turn.id().value())
        .toList();
  }

  private void converse(AgentId agentId, int turns) {
    for (int i = 1; i <= turns; i++) {
      int expected = chatRequests.size() + 1;
      harness.observe(agentId, "turn " + i);
      await().atMost(Duration.ofSeconds(20)).until(() -> chatRequests.size() >= expected);
    }
  }

  @Test
  @DisplayName("does nothing while the story fits in the tail")
  void a_short_story_is_left_alone() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    converse(agentId, MAX_TAIL);
    // Every turn end was heard; none found anything to do.
    await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> true);
    assertThat(summaries.forAgent(agentId)).isEmpty();
    assertThat(summariesWritten).hasValue(0);
  }

  /**
   * The cut is made when a turn's end is heard, on a thread of its own, while the conversation goes
   * on -- so exactly which turn it lands after depends on timing, and a test that pinned it would
   * be pinning a race. What never varies: the range starts at the beginning, the cut leaves between
   * minTail and maxTail turns verbatim, and the next call is built on summary plus tail.
   */
  @Test
  @DisplayName("summarises the head once it outgrows the tail, leaving the tail verbatim")
  void a_long_story_gets_its_head_summarised() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    converse(agentId, MAX_TAIL + 3);
    await().atMost(Duration.ofSeconds(20)).until(() -> !summaries.forAgent(agentId).isEmpty());
    List<Long> ids = turnIds(agentId);

    Summary summary = summaries.forAgent(agentId).getFirst();
    int cut = ids.indexOf(summary.through().value()) + 1;
    assertThat(summary.from()).isEqualTo(new TurnId(ids.getFirst()));
    assertThat(ids.size() - cut).isBetween(MIN_TAIL, MAX_TAIL);
    assertThat(summary.content()).containsExactly(new Block.Text("SUMMARY of " + cut + " turns"));
    // On record: a nessy.summary span that says it wrote, with the model call inside it.
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> "written".equals(recorded.tag(SummaryObservation.NAME, "nessy.summary.outcome")));
    assertThat(recorded.tag(SummaryObservation.NAME, "nessy.summary.kind")).isEqualTo("head");
    assertThat(recorded.tag(ObservedInference.DURATION, "gen_ai.provider.name")).isEqualTo("test");

    // The next call the agent makes is built on it: the summary, then the turns after it.
    converse(agentId, 1);
    InferenceRequest last = chatRequests.getLast();
    assertThat(last.context().summaries()).isNotEmpty();
    long through = last.context().summaries().getLast().through().value();
    assertThat(last.context().turns())
        .extracting(turn -> turn.id().value())
        .containsExactlyElementsOf(turnIds(agentId).stream().filter(id -> id > through).toList())
        .hasSizeLessThanOrEqualTo(MAX_TAIL);
  }

  @Test
  @DisplayName("a later fold is shown the summary so far, and what it writes replaces it")
  void later_folds_replace() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    converse(agentId, MAX_TAIL + 3);
    await().atMost(Duration.ofSeconds(20)).until(() -> !summaries.forAgent(agentId).isEmpty());
    Summary first = summaries.forAgent(agentId).getFirst();
    converse(agentId, MAX_TAIL + 1);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                summaries.forAgent(agentId).getFirst().through().value() > first.through().value());

    List<Long> ids = turnIds(agentId);
    List<Summary> written = summaries.forAgent(agentId);
    assertThat(written).as("one summary per agent").hasSize(1);
    Summary folded = written.getFirst();
    assertThat(folded.from()).as("still from the beginning").isEqualTo(first.from());
    assertThat(text(folded.content()))
        .as("the model was shown the summary so far, and folded it in")
        .contains("after [" + text(first.content()) + "]");
    long through = folded.through().value();
    assertThat(ids.stream().filter(id -> id > through).count())
        .as("what stays verbatim")
        .isBetween((long) MIN_TAIL, (long) MAX_TAIL);
    // And the fold was asked in the shape the engine uses -- the summary, then the turns --
    // ending on an open turn that asks, so the conversation does not end on the assistant's own
    // words with nothing to answer.
    InferenceRequest lastFold = summaryRequests.getLast();
    assertThat(lastFold.context().summaries()).containsExactly(first);
    assertThat(lastFold.context().turns()).hasSizeGreaterThan(1);
    assertThat(lastFold.context().turns().getLast().complete()).isFalse();
    assertThat(lastFold.context().turns().subList(0, lastFold.context().turns().size() - 1))
        .allSatisfy(turn -> assertThat(turn.complete()).isTrue());
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
