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

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Base64;
import java.util.List;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.IdentityCodec;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.StorageCodecConfigurer;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Nessy as a Boot citizen: an {@link InferenceProvider} in, a {@link QueuedHarness} out.
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
 *
 * <p><b>The tables live with the JDBC backend; the codec factory lives here.</b> {@link
 * JdbcBackendAutoConfiguration} owns the schema, because tables are its own and an application with
 * no {@code DataSource} must still start. The codec factory belongs to no backend in particular:
 * every store that turns a value into bytes asks this one for it, so a transform an application
 * configures reaches all of them rather than whichever substrate happened to ask.
 */
@AutoConfiguration(
    // Ordered after Micrometer's, because nessyTokenUsageHandler is @ConditionalOnBean on a
    // MeterRegistry and that condition is evaluated while auto-configurations are being processed
    // -- it sees only what has been defined by the time it runs. Without this, the handler quietly
    // did not exist in applications that had a registry, and the wiring test went on passing
    // because a test's registry is a user bean, which is defined before any auto-configuration.
    //
    // By name rather than by class: every integration this module configures is optional, and
    // naming the class would put Micrometer's metrics module on the compile path of applications
    // that do not measure anything.
    afterName = {
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
    })
@EnableConfigurationProperties(NessyProperties.class)
public class NessyAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(NessyAutoConfiguration.class);

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
   * The one way to build a {@link Codec} in this engine: Jackson, over the context's {@link
   * ObjectMapper}, with the transform the {@link StorageCodecConfigurer} bean returns appended.
   *
   * <p>Here rather than with a backend because it belongs to none of them in particular. Every
   * store that turns a value into bytes reaches for this one factory, so an application that
   * configures a transform gets it everywhere rather than on whichever substrate happened to ask.
   *
   * <p>The bean keeps the two layers apart ({@link StorageLayers}), which is how a backend can hash
   * a payload's content before the transform. Nothing appended means each codec it creates is the
   * plain Jackson codec itself, with no noop codec wrapped around it; reference equality against
   * {@link IdentityCodec#INSTANCE} is what tells the two cases apart: a configurer that composes
   * nothing hands the same instance straight back.
   */
  @Bean
  @ConditionalOnMissingBean
  public CodecFactory codecFactory(ObjectMapper mapper, StorageCodecConfigurer configurer) {
    return new StorageLayers(
        new JacksonCodecFactory(mapper), configurer.configure(IdentityCodec.INSTANCE));
  }

  /**
   * Nothing appended, for an application that has not declared a {@link StorageCodecConfigurer}.
   */
  @Bean
  @ConditionalOnMissingBean
  public StorageCodecConfigurer storageCodecConfigurer() {
    return original -> original;
  }

  /**
   * Token counts as semconv's histogram, whenever there is a meter registry to hold them.
   *
   * <p>A bean rather than a handler registered by hand inside another bean's factory method, which
   * is where this used to live: Boot's {@code ObservationRegistryConfigurer} collects every {@link
   * io.micrometer.observation.ObservationHandler} bean and registers it, so declaring one is all
   * this takes. Registering it as a side effect of building the queued door's factory meant an
   * application that used only the direct door got no token metric at all -- a door deciding what
   * is observed about inference, which is not a door's business.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(MeterRegistry.class)
  public TokenUsageHandler nessyTokenUsageHandler(MeterRegistry meters) {
    return new TokenUsageHandler(meters);
  }
}
