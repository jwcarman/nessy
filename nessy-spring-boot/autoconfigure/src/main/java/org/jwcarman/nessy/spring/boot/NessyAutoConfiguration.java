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
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

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
 * <p><b>The tables and the codec factory live with the JDBC backend, not here.</b> {@link
 * JdbcBackendAutoConfiguration} owns both, so excluding this class -- or running with no {@code
 * DataSource} at all -- never orphans the backend.
 */
@AutoConfiguration
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

  /** Says what will actually answer, before a single turn runs. */
  @Bean
  @ConditionalOnMissingBean
  public InferenceReport nessyInferenceReport(
      ObjectProvider<InferenceProvider> providers,
      NessyProperties properties,
      Environment environment) {
    return new InferenceReport(providers, properties, environment);
  }
}
