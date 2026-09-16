package org.jwcarman.nessy.memory.summarizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.lease.JdbcLeases;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.store.Schemas;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** What a fold does when the model does not cooperate: nothing, and it says so. */
@DisplayName("A fold that comes back wrong")
class HeadSummarizerFoldTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType CHAT = new AgentType("chat-fold");
  private static final int MAX_TAIL = 3;
  private static final int MIN_TAIL = 1;

  private final AtomicInteger folds = new AtomicInteger();
  private final AtomicInteger chats = new AtomicInteger();
  private HikariDataSource dataSource;
  private DefaultHarnessFactory factory;
  private JdbcSummaries summaries;

  private Harness<String> start(InferenceResult foldResult) {
    InferenceProvider model =
        (request, narrator) -> {
          if (request.systemPrompt().value().equals(HeadSummarizer.PROMPT)) {
            folds.incrementAndGet();
            return foldResult;
          }
          chats.incrementAndGet();
          return new InferenceResult.Answer(List.of(new Block.Text("a lake monster")));
        };
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
    HeadSummarizer summarizer =
        HeadSummarizer.create(
            c ->
                c.agentType(CHAT)
                    .summaries(summaries)
                    .histories(factory.histories())
                    .leases(new JdbcLeases(dataSource))
                    .inference(model, InferenceOptions.of("m"))
                    .tail(MAX_TAIL, MIN_TAIL)
                    .leaseTtl(Duration.ofSeconds(30)));
    return factory.create(
        String.class,
        h ->
            h.agentType(CHAT)
                .systemPrompt("You are a test assistant.")
                .inference(in -> in.context(ctx -> ctx.summaries(summaries).maxTail(MAX_TAIL)))
                .effects(e -> e.pollInterval(Duration.ofMillis(100)))
                .listener(summarizer.listener()));
  }

  @AfterEach
  void stop() {
    factory.close();
    dataSource.close();
  }

  private void converse(Harness<String> harness, AgentId agentId, int turns) {
    for (int i = 1; i <= turns; i++) {
      int expected = chats.get() + 1;
      harness.observe(agentId, "turn " + i);
      await().atMost(Duration.ofSeconds(20)).until(() -> chats.get() >= expected);
    }
  }

  @Test
  void a_fault_leaves_the_summary_as_it_was_and_the_next_turn_tries_again() {
    Harness<String> harness =
        start(new InferenceResult.Fault(new Failure.Transient("model is having a day")));
    AgentId agentId = new AgentId(UUID.randomUUID());

    converse(harness, agentId, MAX_TAIL + 3);

    await().atMost(Duration.ofSeconds(20)).until(() -> folds.get() >= 2);
    assertThat(summaries.forAgent(agentId)).isEmpty();
  }

  @Test
  void an_empty_answer_keeps_what_there_was() {
    Harness<String> harness = start(new InferenceResult.Answer(List.of(new Block.Text("   "))));
    AgentId agentId = new AgentId(UUID.randomUUID());

    converse(harness, agentId, MAX_TAIL + 3);

    await().atMost(Duration.ofSeconds(20)).until(() -> folds.get() >= 1);
    assertThat(summaries.forAgent(agentId)).isEmpty();
  }
}
