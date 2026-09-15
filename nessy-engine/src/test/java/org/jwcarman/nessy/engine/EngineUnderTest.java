package org.jwcarman.nessy.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.engine.store.AgentStateRepository;
import org.jwcarman.nessy.engine.store.JdbcHistoryStore;
import org.jwcarman.nessy.engine.store.StorageCodec;
import org.jwcarman.nessy.engine.token.CharacterCountEstimator;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
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

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private final HikariDataSource dataSource;
  private final DefaultHarnessFactory harnesses;
  private final JdbcHistoryStore history;
  private final JdbcClient jdbc;
  private final AgentStateRepository states;

  public EngineUnderTest(InferenceProvider provider, AgentEventListener listener) {
    this(provider, listener, ObservationRegistry.NOOP);
  }

  public EngineUnderTest(
      InferenceProvider provider, AgentEventListener listener, ObservationRegistry observations) {
    this(provider, listener, observations, Optional.empty());
  }

  /** With something done to every stored byte, which the fixture's own reader must undo too. */
  public EngineUnderTest(InferenceProvider provider, Codec<byte[]> storage) {
    this(provider, AgentEventListener.none(), ObservationRegistry.NOOP, Optional.of(storage));
  }

  private EngineUnderTest(
      InferenceProvider provider,
      AgentEventListener listener,
      ObservationRegistry observations,
      Optional<Codec<byte[]>> storage) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    this.dataSource = new HikariDataSource(config);

    // Opt-in, and the whole point of the convention: an application says when its schema is
    // created rather than having a framework run a file named schema.sql behind its back.
    Schemas.initialize(dataSource);

    // Held for assertions only. The engine builds its own from the same DataSource; these are
    // stateless readers over the same tables, and a test that reached into the engine's would be
    // asserting on its internals rather than on what it wrote down.
    this.jdbc = JdbcClient.create(dataSource);
    this.states = new AgentStateRepository(jdbc);
    CodecFactory jackson = new JacksonCodecFactory(JsonMapper.builder().build());
    this.history =
        new JdbcHistoryStore(
            jdbc,
            storage.map(t -> StorageCodec.of(t).after(jackson)).orElse(jackson),
            new CharacterCountEstimator());

    this.harnesses =
        new DefaultHarnessFactory(
            engine -> {
              engine
                  .dataSource(dataSource)
                  .inference(provider, InferenceOptions.of("a-model"))
                  .listener(listener)
                  .observations(observations);
              storage.ifPresent(engine::storage);
            });
  }

  public EngineUnderTest(InferenceProvider provider) {
    this(provider, AgentEventListener.none());
  }

  public DefaultHarnessFactory harnesses() {
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
    harnesses.close();
    dataSource.close();
  }
}
