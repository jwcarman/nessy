/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.spring.boot;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.StorageConfig;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Nessy as a Boot citizen: a {@code DataSource} and an {@link InferenceProvider} in, a {@link
 * QueuedHarness} out.
 *
 * <p><b>The engine is a library, and this is the only thing that makes it a framework.</b>
 * Everything below assembles collaborators an application could assemble itself -- and one test in
 * the engine does exactly that, by hand, precisely so this class never becomes load-bearing. If the
 * engine ever needs an {@code ApplicationContext} to run, that test stops compiling before this one
 * does.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}: an application that declares its own is
 * choosing it explicitly, and this backs off rather than competing.
 *
 * <p><b>Turning it off is Spring's job, not ours.</b> This refuses to start without {@code
 * nessy.model}, which is right for an application that wants Nessy and wrong for one that merely
 * shares a classpath with it -- several Boot apps in one JVM, as an acceptance test has. That case
 * is real, and the answer is the mechanism Boot already has: {@code
 * spring.autoconfigure.exclude=org.jwcarman.nessy.spring.boot.NessyAutoConfiguration}. A {@code
 * nessy.enabled} property of our own was a second way to say the same thing, and every
 * auto-configuration added afterwards was a fresh chance to forget to honour it -- which is exactly
 * what happened when the backend auto-configurations arrived and left the disabled path asking for
 * a codec factory that was never going to exist.
 */
@AutoConfiguration(
    after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class},
    afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
    // Named rather than imported: Substrate is optional, and an application without it never
    // pulls this class in. Ordered ahead so the CodecFactory bean below exists by the time
    // Substrate's own journal factory asks its @ConditionalOnBean(CodecFactory.class) whether one
    // is there.
    beforeName = "org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration")
@EnableConfigurationProperties(NessyProperties.class)
public class NessyAutoConfiguration {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(NessyAutoConfiguration.class);

  /**
   * The one way to build a {@link Codec} in this engine: Jackson, over the context's {@link
   * ObjectMapper}, with every {@code Customizer<StorageConfig>} bean's transform composed on after
   * it in {@link ObjectProvider#orderedStream() orderedStream} order.
   *
   * <p>No customizers means the plain Jackson factory is handed back directly -- the noop default,
   * with no noop object wrapping it.
   */
  @Bean
  @ConditionalOnMissingBean
  public CodecFactory codecFactory(
      ObjectMapper mapper, ObjectProvider<Customizer<StorageConfig>> customizers) {
    CodecFactory jackson = new JacksonCodecFactory(mapper);
    ComposingStorageConfig config = new ComposingStorageConfig();
    customizers.orderedStream().forEach(customizer -> customizer.customize(config));
    Codec<byte[]> transform = config.transform;
    if (transform == null) {
      return jackson;
    }
    return new CodecFactory() {
      @Override
      public <T> Codec<T> create(TypeRef<T> type) {
        return jackson.create(type).andThen(transform);
      }
    };
  }

  /** Collects every appended transform into one, composed outward in append order. */
  private static final class ComposingStorageConfig implements StorageConfig {

    private @Nullable Codec<byte[]> transform;

    @Override
    public StorageConfig append(Codec<byte[]> next) {
      Objects.requireNonNull(next, "transform must not be null");
      transform = transform == null ? next : transform.andThen(next);
      return this;
    }
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

  /** Says what will actually answer, before a single turn runs. */
  @Bean
  @ConditionalOnMissingBean
  public InferenceReport nessyInferenceReport(
      ObjectProvider<org.jwcarman.nessy.inference.InferenceProvider> providers,
      NessyProperties properties,
      org.springframework.core.env.Environment environment) {
    return new InferenceReport(providers, properties, environment);
  }
}
