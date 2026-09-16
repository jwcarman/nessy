package org.jwcarman.nessy.spring.boot;

import io.micrometer.observation.ObservationRegistry;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.engine.store.InferenceContexts;
import org.jwcarman.nessy.engine.store.StorageCodec;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;

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
   * The schema, created where the application says so.
   *
   * <p>Opt-in on purpose: {@code nessy.initialize-schema} defaults to true because an application
   * that added the starter wants the tables, but an application that manages its own migrations
   * turns it off and nothing runs a DDL file behind its back.
   */
  @Bean
  @ConditionalOnMissingBean(name = "nessySchema")
  public NessySchema nessySchema(DataSource dataSource, NessyProperties properties) {
    boolean initialize = Boolean.TRUE.equals(properties.initializeSchema());
    if (initialize) {
      Schemas.initialize(dataSource);
    }
    return new NessySchema(initialize);
  }

  /**
   * A marker, so every bean that needs tables can depend on the tables existing.
   *
   * @param initialized whether this starter ran the DDL, or left the tables to the application
   */
  public record NessySchema(boolean initialized) {}

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

  @Bean
  @ConditionalOnMissingBean
  public DefaultHarnessFactory nessyHarnessFactory(
      DataSource dataSource,
      ReplyTokens replyTokens,
      InferenceProvider models,
      NessyProperties properties,
      NessySchema schema,
      ObjectProvider<ObservationRegistry> registries,
      ObjectProvider<StorageCodec> storage) {

    ObservationRegistry observations = registries.getIfAvailable(() -> ObservationRegistry.NOOP);
    // Wrapped only when there is somewhere to report to, so an application that is not tracing
    // pays for no wrapper at all -- and when it is, the chat span lands inside the effect span
    // that caused it rather than starting a trace of its own.
    InferenceProvider provider =
        ObservationRegistry.NOOP.equals(observations)
            ? models
            : Observed.inference(models, properties.provider(), observations);

    return new DefaultHarnessFactory(
        engine -> {
          engine
              .dataSource(dataSource)
              .inference(
                  provider, new InferenceOptions(requireModel(properties), properties.maxTokens()))
              .observations(observations)
              .replyTokens(replyTokens);
          // What is done to every stored byte after Jackson, when the application declared it:
          // compression, encryption. Declared as a StorageCodec bean, because a bean of a plain
          // Codec<byte[]> names nothing in particular.
          storage.ifAvailable(engine::storage);
        });
  }

  /**
   * Every {@link AgentEventListener} bean, attached engine-wide once every bean exists -- after
   * rather than at the factory's making, so a listener that reads the story (through the factory)
   * is not a circle.
   */
  @Bean
  public SmartInitializingSingleton nessyListeners(
      DefaultHarnessFactory factory, ObjectProvider<AgentEventListener> listeners) {
    return () -> listeners.orderedStream().forEach(factory::listener);
  }

  /** The story, for an application that shows what its agents said. */
  @Bean
  @ConditionalOnMissingBean
  public TurnHistories nessyHistories(DefaultHarnessFactory factory) {
    return factory.histories();
  }

  /** What each model call was shown, for evals, critics and anyone debugging a call. */
  @Bean
  @ConditionalOnMissingBean
  public InferenceContexts nessyInferenceContexts(DefaultHarnessFactory factory) {
    return factory.inferenceContexts();
  }

  @Bean
  @ConditionalOnMissingBean
  public Replies nessyReplies(DefaultHarnessFactory factory) {
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
      DefaultHarnessFactory factory,
      NessyProperties properties,
      ObjectProvider<Tool<?>> tools,
      ObjectProvider<ObservationRenderer<String>> renderers,
      ObjectProvider<ObservationRegistry> registries,
      ObjectProvider<SystemPromptSource> prompts) {

    ObservationRegistry observations = registries.getIfAvailable(() -> ObservationRegistry.NOOP);
    List<Tool<?>> declared = tools.orderedStream().toList();
    // A templated prompt when an engine is on the classpath (PromptAutoConfiguration), or one
    // the application declared; the plain property otherwise.
    SystemPromptSource systemPrompt =
        prompts.getIfAvailable(
            () -> SystemPromptSource.constant(new SystemPrompt(properties.resolveSystemPrompt())));
    ObservationRenderer<String> renderer =
        renderers.getIfAvailable(() -> said -> List.of(new Block.Text(said)));

    return factory.create(
        String.class,
        config -> {
          config
              .agentType(new AgentType(properties.type()))
              .systemPrompt(systemPrompt)
              .observationRenderer(renderer);
          // The harness observes every tool it is given itself, so nothing is wrapped here.
          declared.forEach(config::tool);
        });
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
