package org.jwcarman.nessy.spring.boot;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.engine.harness.HarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.engine.store.JdbcHistoryStore;
import org.jwcarman.nessy.engine.token.CharacterCountEstimator;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nessy as a Boot citizen: a {@code DataSource} and an {@link InferenceProvider} in, a {@link
 * Harness} out.
 *
 * <p><b>The engine is a library, and this is the only thing that makes it a framework.</b>
 * Everything below assembles collaborators an application could assemble itself -- and one test in
 * the engine does exactly that, by hand, precisely so this class never becomes load-bearing. If the
 * engine ever needs an {@code ApplicationContext} to run, that test stops compiling before this one
 * does.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}: an application that declares its own is
 * choosing it explicitly, and this backs off rather than competing.
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@EnableConfigurationProperties(NessyProperties.class)
public class NessyAutoConfiguration {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(NessyAutoConfiguration.class);

  /**
   * <b>No in-memory fallback.</b> There used to be one: no {@code DataSource} meant an embedded H2
   * and a loud warning. The warning was the tell -- every query this engine rests on is
   * PostgreSQL's ({@code FOR UPDATE SKIP LOCKED} to claim work, {@code LEAST} to cap a deadline),
   * so the fallback did not run a degraded Nessy, it ran one that fails on the first turn. Saying
   * so at startup is kinder than an embedded database that looks like it worked.
   */
  @Bean
  @ConditionalOnMissingBean
  public Clock nessyClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public CodecFactory nessyCodecs(ObjectProvider<JsonMapper> mappers) {
    return new JacksonCodecFactory(mappers.getIfAvailable(() -> JsonMapper.builder().build()));
  }

  @Bean
  @ConditionalOnMissingBean
  public InputSchemaGenerator nessyInputSchemas() {
    return new VictoolsInputSchemaGenerator();
  }

  /**
   * The schema, created where the application says so.
   *
   * <p>Opt-in on purpose: {@code nessy.initialize-schema} defaults to true because an application
   * that added the starter wants the tables, but an application that manages its own migrations
   * turns it off and nothing runs a DDL file behind its back.
   */
  @Bean
  @ConditionalOnMissingBean(name = "nessySchema")
  public NessySchema nessySchema(DataSource dataSource, NessyProperties properties) {
    if (properties.initializeSchema()) {
      Schemas.initialize(dataSource);
    }
    return new NessySchema();
  }

  /** A marker, so every bean that needs tables can depend on the tables existing. */
  public record NessySchema() {}

  @Bean
  @ConditionalOnMissingBean
  public ReplyTokens nessyReplyTokens(NessyProperties properties) {
    List<String> keys = properties.replyTokenEncryptionKeys();
    if (keys.isEmpty()) {
      log.warn(
          "NESSY REPLY TOKENS ARE EPHEMERAL: no nessy.reply-token-encryption-keys configured, so"
              + " any approval parked on a person becomes unanswerable after a restart. Configure"
              + " a base64 32-byte AES key for anything that is not a test:"
              + " openssl rand -base64 32");
      return ReplyTokens.ephemeral();
    }
    return ReplyTokens.withKeys(
        keys.stream().map(key -> Base64.getDecoder().decode(key)).toArray(byte[][]::new));
  }

  /**
   * A timer, and only a timer.
   *
   * <p><b>This does not perform effects.</b> {@code EffectDispatcher} creates its own
   * virtual-thread executor and submits every model call, tool call and approval to it, so the
   * threads that matter are the engine's and nothing here configures them. All this scheduler ever
   * runs is the periodic look for due work: one short query, then a handoff.
   *
   * <p>Which is why one thread is enough, and why it is virtual anyway -- the query is still a
   * blocking call, and there is no reason for a timer to own a platform thread.
   */
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "nessyScheduler")
  public TaskScheduler nessyScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setVirtualThreads(true);
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("nessy-");
    scheduler.initialize();
    return scheduler;
  }

  @Bean
  @ConditionalOnMissingBean
  public PlatformTransactionManager nessyTransactionManager(DataSource dataSource) {
    return new JdbcTransactionManager(dataSource);
  }

  @Bean
  @ConditionalOnMissingBean
  public JdbcHistoryStore nessyHistory(
      DataSource dataSource, CodecFactory codecs, NessySchema schema) {
    return new JdbcHistoryStore(
        JdbcClient.create(dataSource), codecs, new CharacterCountEstimator());
  }

  /**
   * Silent unless an application says otherwise.
   *
   * <p>Narration is best-effort and never durable, so a default that said something would be a
   * default that costs every application a log line per token. An application that wants a console,
   * a journal or a websocket declares a {@link Narrator} bean.
   */
  @Bean
  @ConditionalOnMissingBean
  public Narrator nessyNarrator() {
    return Narrator.silent();
  }

  @Bean
  @ConditionalOnMissingBean
  public HarnessFactory nessyHarnessFactory(
      DataSource dataSource,
      CodecFactory codecs,
      InputSchemaGenerator schemas,
      Narrator narrator,
      ReplyTokens replyTokens,
      PlatformTransactionManager transactions,
      TaskScheduler scheduler,
      Clock clock,
      InferenceProvider models,
      NessyProperties properties,
      NessySchema schema,
      ObjectProvider<ObservationRegistry> registries,
      ObjectProvider<JsonMapper> mappers) {

    ObservationRegistry observations = registries.getIfAvailable(() -> ObservationRegistry.NOOP);
    // Wrapped only when there is somewhere to report to, so an application that is not tracing
    // pays for no wrapper at all -- and when it is, the chat span lands inside the effect span
    // that caused it rather than starting a trace of its own.
    InferenceProvider provider =
        ObservationRegistry.NOOP.equals(observations)
            ? models
            : Observed.inference(models, properties.provider(), observations);

    return new HarnessFactory(
        engine ->
            engine
                .dataSource(dataSource)
                .inference(
                    provider,
                    new InferenceOptions(
                        requireModel(properties),
                        properties.maxTokens(),
                        properties.capabilities()))
                .codecs(codecs)
                .schemas(schemas)
                .mapper(mappers.getIfAvailable(() -> JsonMapper.builder().build()))
                .narrator(narrator)
                .observations(observations)
                .replyTokens(replyTokens)
                .transactionManager(transactions)
                .scheduler(scheduler)
                .clock(clock));
  }

  @Bean
  @ConditionalOnMissingBean
  public Replies nessyReplies(HarnessFactory factory) {
    return factory.replies();
  }

  /**
   * The one harness an application gets for free, over {@code String} observations.
   *
   * <p>Every {@link Tool} bean is bound to it, and every tool is wrapped for observability when
   * there is a registry to report to. An application wanting several agent types declares its own
   * harnesses from the factory instead.
   */
  @Bean
  @ConditionalOnMissingBean
  public Harness<String> nessyHarness(
      HarnessFactory factory,
      NessyProperties properties,
      ObjectProvider<Tool<?>> tools,
      ObjectProvider<ObservationRenderer<String>> renderers,
      ObjectProvider<ObservationRegistry> registries) {

    ObservationRegistry observations = registries.getIfAvailable(() -> ObservationRegistry.NOOP);
    List<Tool<?>> declared = tools.orderedStream().toList();
    String systemPrompt = properties.resolveSystemPrompt();
    ObservationRenderer<String> renderer =
        renderers.getIfAvailable(() -> said -> List.of(new Block.Text(said)));

    return factory.create(
        String.class,
        config -> {
          config
              .agentType(new AgentType(properties.type()))
              .systemPrompt(systemPrompt)
              .observationRenderer(renderer);
          declared.forEach(tool -> bind(config, tool, observations));
        });
  }

  private static <I> void bind(
      HarnessConfig<String> config, Tool<I> tool, ObservationRegistry observations) {
    config.tool(
        ObservationRegistry.NOOP.equals(observations) ? tool : Observed.tool(tool, observations));
  }

  private static String requireModel(NessyProperties properties) {
    String model = properties.model();
    if (model == null || model.isBlank()) {
      throw new IllegalStateException(
          "nessy.model must name the model these agents talk to; it is sent to your"
              + " InferenceProvider bean with every call");
    }
    return model;
  }
}
