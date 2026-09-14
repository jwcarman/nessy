package org.jwcarman.nessy.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.engine.harness.HarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.engine.store.AgentStateRepository;
import org.jwcarman.nessy.engine.store.JdbcEffectStore;
import org.jwcarman.nessy.engine.store.JdbcHistoryStore;
import org.jwcarman.nessy.engine.token.CharacterCountEstimator;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A whole engine, wired by hand against a real PostgreSQL.
 *
 * <p><b>No Spring Boot, deliberately.</b> The engine's own POM claims it is a library — that Spring
 * is used for {@code JdbcClient} and {@code TaskScheduler} and nothing else, and that no {@code
 * ApplicationContext} is required to run one. This class is the only proof of that claim: if the
 * engine ever grows a dependency on being in a container, this stops compiling.
 *
 * <p><b>Real PostgreSQL, not H2.</b> The queries this engine rests on are PostgreSQL's: {@code FOR
 * UPDATE SKIP LOCKED} for claiming work, {@code LEAST} for capping a deadline, and parameters that
 * PostgreSQL refuses as a bare {@code Instant} while H2 accepts them happily. A test on H2 would
 * pass against exactly the bugs that matter.
 *
 * <p>One container for the whole class, and agents are addressed by fresh random ids, so tests do
 * not have to clean up after each other.
 */
public final class EngineUnderTest implements AutoCloseable {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private final HikariDataSource dataSource;
  private final ThreadPoolTaskScheduler scheduler;
  private final HarnessFactory harnesses;
  private final JdbcHistoryStore history;
  private final JdbcClient jdbc;
  private final AgentStateRepository states;

  public EngineUnderTest(InferenceProvider provider, Narrator narrator) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    this.dataSource = new HikariDataSource(config);

    // Opt-in, and the whole point of the convention: an application says when its schema is
    // created rather than having a framework run a file named schema.sql behind its back.
    Schemas.initialize(dataSource);

    this.jdbc = JdbcClient.create(dataSource);
    CodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
    this.history = new JdbcHistoryStore(jdbc, codecs, new CharacterCountEstimator());

    this.scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.initialize();

    this.states = new AgentStateRepository(jdbc);

    DataSourceTransactionManager transactions = new JdbcTransactionManager(dataSource);
    InputSchemaGenerator schemas = new VictoolsInputSchemaGenerator();

    this.harnesses =
        new HarnessFactory(
            codecs,
            states,
            history,
            history,
            history,
            schemas,
            JsonMapper.builder().build(),
            narrator,
            ReplyTokens.ephemeral(),
            new JdbcEffectStore(jdbc, codecs),
            transactions,
            scheduler,
            Clock.systemUTC(),
            provider,
            InferenceOptions.of("a-model"),
            new RetryPolicy.Never());
  }

  public EngineUnderTest(InferenceProvider provider) {
    this(provider, Narrator.silent());
  }

  public HarnessFactory harnesses() {
    return harnesses;
  }

  public JdbcHistoryStore history() {
    return history;
  }

  public JdbcClient jdbc() {
    return jdbc;
  }

  public AgentStateRepository states() {
    return states;
  }

  public Replies replies() {
    return harnesses.replies();
  }

  @Override
  public void close() {
    scheduler.shutdown();
    dataSource.close();
  }
}
