package org.jwcarman.nessy.memory.summarizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
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

  private final AtomicInteger summariesWritten = new AtomicInteger();
  private final InferenceProvider model =
      (request, narrator) -> {
        if (request.systemPrompt().value().equals(HeadSummarizer.PROMPT)) {
          summariesWritten.incrementAndGet();
          String seen =
              ((Block.Text) request.context().turns().getFirst().observation().blocks().getFirst())
                  .text();
          long turns = seen.lines().filter(line -> line.startsWith("user: ")).count();
          return new InferenceResult.Answer(List.of(new Block.Text("SUMMARY of " + turns)));
        }
        chatRequests.add(request);
        return new InferenceResult.Answer(List.of(new Block.Text("a lake monster")));
      };

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
    factory =
        new DefaultHarnessFactory(
            engine -> engine.dataSource(dataSource).inference(model, InferenceOptions.of("m")));
    summaries = new JdbcSummaries(dataSource, CHAT);
    harness =
        factory.create(
            String.class,
            h ->
                h.agentType(CHAT)
                    .systemPrompt("You are a test assistant.")
                    .inference(in -> in.context(ctx -> ctx.summaries(summaries).maxTail(MAX_TAIL)))
                    .effects(e -> e.pollInterval(Duration.ofMillis(100))));
    summarizer =
        HeadSummarizer.create(
            c ->
                c.agentType(CHAT)
                    .summaries(summaries)
                    .histories(factory.histories())
                    .leases(new JdbcLeases(dataSource))
                    .inference(model, InferenceOptions.of("m"))
                    .tail(MAX_TAIL, MIN_TAIL));
  }

  @AfterEach
  void stop() {
    factory.close();
    dataSource.close();
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
    summarizer.sweep();
    assertThat(summaries.forAgent(agentId)).isEmpty();
    assertThat(summariesWritten).hasValue(0);
  }

  @Test
  @DisplayName("summarises the head once it outgrows the tail, leaving minTail verbatim")
  void a_long_story_gets_its_head_summarised() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    converse(agentId, MAX_TAIL + 3); // 9 complete turns: 7 are summarised, 2 stay verbatim
    List<Long> ids = turnIds(agentId);
    assertThat(ids).hasSize(9);

    summarizer.sweep();

    assertThat(summaries.forAgent(agentId))
        .singleElement()
        .satisfies(
            summary -> {
              assertThat(summary.from()).isEqualTo(new TurnId(ids.getFirst()));
              assertThat(summary.through()).isEqualTo(new TurnId(ids.get(6)));
              assertThat(summary.content()).containsExactly(new Block.Text("SUMMARY of 7"));
            });

    // And the next call the agent makes is built on it: the summary, then the turns after it.
    converse(agentId, 1);
    InferenceRequest last = chatRequests.getLast();
    assertThat(last.context().summaries()).hasSize(1);
    assertThat(last.context().turns())
        .extracting(turn -> turn.id().value())
        .containsExactly(ids.get(7), ids.get(8), turnIds(agentId).getLast());

    // A second sweep finds the head short again and writes nothing more.
    summarizer.sweep();
    assertThat(summaries.forAgent(agentId)).hasSize(1);
    assertThat(summariesWritten).hasValue(1);
  }

  @Test
  @DisplayName("each cut is a new summary of its own range; earlier ones are never rewritten")
  void later_cuts_append() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    converse(agentId, MAX_TAIL + 3);
    summarizer.sweep();
    converse(agentId, MAX_TAIL + 1); // 2 verbatim + 7 new = 9 after the summary: over again
    summarizer.sweep();

    List<Long> ids = turnIds(agentId); // 16 turns; the last 2 stay verbatim
    List<Summary> written = summaries.forAgent(agentId);
    assertThat(written).hasSize(2);
    assertThat(written.get(0).from()).isEqualTo(new TurnId(ids.getFirst()));
    assertThat(written.get(0).through()).isEqualTo(new TurnId(ids.get(6)));
    assertThat(written.get(1).from()).isEqualTo(new TurnId(ids.get(7)));
    assertThat(written.get(1).through()).isEqualTo(new TurnId(ids.get(ids.size() - 1 - MIN_TAIL)));
  }
}
