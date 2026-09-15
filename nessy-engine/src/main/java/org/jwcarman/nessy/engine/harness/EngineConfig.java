package org.jwcarman.nessy.engine.harness;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.engine.token.CharacterCountEstimator;
import org.jwcarman.nessy.engine.token.TokenEstimator;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an engine needs, and what it will assume if not told.
 *
 * <p><b>Two required things: somewhere to keep agents and something to ask.</b> Everything else
 * below has a default that is stated here rather than repeated by every caller -- which is the
 * whole point. Assembling the stores took seven identical lines in the starter and seven more in
 * the engine's own test fixture, and a store handed in three times over (a {@code JdbcHistoryStore}
 * is also a {@code TurnHistories} and a {@code ToolCallHistories}) was three chances to pass three
 * different things.
 *
 * <p>Customizer-shaped, like {@link org.jwcarman.nessy.api.HarnessConfig} and the configs beneath
 * it: an application says what it wants and stays silent about the rest.
 */
public final class EngineConfig {

  private DataSource dataSource;
  private InferenceProvider provider;
  private InferenceOptions options;

  private CodecFactory codecs;
  private ObjectMapper mapper;
  private InputSchemaGenerator schemas;
  private TokenEstimator tokens;
  private Narrator narrator = Narrator.silent();
  private ObservationRegistry observations = ObservationRegistry.NOOP;
  private ReplyTokens replyTokens;
  private PlatformTransactionManager transactions;
  private TaskScheduler scheduler;
  private Clock clock = Clock.systemUTC();
  private RetryPolicy retryPolicy = new RetryPolicy.Never();

  EngineConfig() {}

  /** Where agents, their stories and their outstanding work are kept. Required. */
  public EngineConfig dataSource(DataSource dataSource) {
    this.dataSource = dataSource;
    return this;
  }

  /**
   * The model every agent type talks to, and the terms it is asked on.
   *
   * <p>One provider for the engine because a provider is a connection; which model to call travels
   * per request, so an agent type wanting a different one overrides it on its harness.
   */
  public EngineConfig inference(InferenceProvider provider, InferenceOptions options) {
    this.provider = provider;
    this.options = options;
    return this;
  }

  /** How hard an inference is worth trying. Defaults to not at all. */
  public EngineConfig retryPolicy(RetryPolicy retryPolicy) {
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    return this;
  }

  /** How stored state, history and effects are encoded. Defaults to Jackson. */
  public EngineConfig codecs(CodecFactory codecs) {
    this.codecs = codecs;
    return this;
  }

  /** Reads and writes the JSON that tool arguments travel as. Defaults to a plain mapper. */
  public EngineConfig mapper(ObjectMapper mapper) {
    this.mapper = mapper;
    return this;
  }

  /** Describes a tool's arguments to a model. Defaults to victools. */
  public EngineConfig schemas(InputSchemaGenerator schemas) {
    this.schemas = schemas;
    return this;
  }

  /** Roughly what a message costs a model's context. Defaults to counting characters. */
  public EngineConfig tokenEstimator(TokenEstimator tokens) {
    this.tokens = tokens;
    return this;
  }

  /** Where an agent says what it is doing. Defaults to saying nothing. */
  public EngineConfig narrator(Narrator narrator) {
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
    return this;
  }

  /**
   * Where spans go. Defaults to {@link ObservationRegistry#NOOP}, which is the whole of switching
   * tracing off: no carrier is captured, no column is written, no span is opened.
   */
  public EngineConfig observations(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    return this;
  }

  /**
   * How a deferred answer finds its way back. Defaults to a key that dies with this process, so an
   * approval parked on a person becomes unanswerable after a restart -- fine for a test, and the
   * reason an application configures one.
   */
  public EngineConfig replyTokens(ReplyTokens replyTokens) {
    this.replyTokens = replyTokens;
    return this;
  }

  /** Defaults to one over the {@code DataSource}. */
  public EngineConfig transactionManager(PlatformTransactionManager transactions) {
    this.transactions = transactions;
    return this;
  }

  /**
   * The timer that says when to look for due work. Defaults to a single virtual thread.
   *
   * <p>It never performs an effect -- {@code EffectDispatcher} has its own virtual-thread executor
   * for that -- so one is enough.
   */
  public EngineConfig scheduler(TaskScheduler scheduler) {
    this.scheduler = scheduler;
    return this;
  }

  public EngineConfig clock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    return this;
  }

  // ---- what the factory reads ------------------------------------------------------------

  DataSource requiredDataSource() {
    if (dataSource == null) {
      throw new IllegalStateException(
          "an engine needs a DataSource: agents, their stories and their outstanding work are all"
              + " rows, and there is nowhere to keep them");
    }
    return dataSource;
  }

  InferenceProvider requiredProvider() {
    if (provider == null) {
      throw new IllegalStateException(
          "an engine needs an InferenceProvider: it is the thing an agent asks, and there is"
              + " nothing to ask without one");
    }
    return provider;
  }

  InferenceOptions requiredOptions() {
    if (options == null) {
      throw new IllegalStateException("inference(provider, options) needs both");
    }
    return options;
  }

  CodecFactory codecs() {
    return codecs != null ? codecs : new JacksonCodecFactory(JsonMapper.builder().build());
  }

  ObjectMapper mapper() {
    return mapper != null ? mapper : JsonMapper.builder().build();
  }

  InputSchemaGenerator schemas() {
    return schemas != null ? schemas : new VictoolsInputSchemaGenerator();
  }

  TokenEstimator tokenEstimator() {
    return tokens != null ? tokens : new CharacterCountEstimator();
  }

  Narrator narrator() {
    return narrator;
  }

  ObservationRegistry observations() {
    return observations;
  }

  ReplyTokens replyTokens() {
    return replyTokens != null ? replyTokens : ReplyTokens.ephemeral();
  }

  PlatformTransactionManager transactions(DataSource resolved) {
    return transactions != null
        ? transactions
        : new org.springframework.jdbc.support.JdbcTransactionManager(resolved);
  }

  TaskScheduler scheduler() {
    if (scheduler != null) {
      return scheduler;
    }
    ThreadPoolTaskScheduler created = new ThreadPoolTaskScheduler();
    created.setVirtualThreads(true);
    created.setPoolSize(1);
    created.setThreadNamePrefix("nessy-");
    created.initialize();
    return created;
  }

  Clock clock() {
    return clock;
  }

  RetryPolicy retryPolicy() {
    return retryPolicy;
  }
}
