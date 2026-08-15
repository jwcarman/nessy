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
package org.jwcarman.nessy.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root of Nessy's Spring Boot configuration surface, bound from any {@code nessy.*} property.
 *
 * <p>{@link Jdbc#enabled()} and {@link Jdbc#bootstrapSchema()} are boxed {@link Boolean}s rather
 * than primitives so an absent property is distinguishable from an explicit {@code false}: {@link
 * #jdbc} itself binds to {@code null} when no {@code nessy.jdbc.*} property is set at all. {@link
 * Jdbc#enabled()} itself is never read in Java — {@link
 * org.jwcarman.nessy.autoconfigure.JdbcPersistenceAutoConfiguration}'s
 * {@code @ConditionalOnProperty} binds the {@code nessy.jdbc.enabled} property straight from the
 * environment (see {@link org.jwcarman.nessy.autoconfigure.JdbcProperties#JDBC_ENABLED_PROPERTY}),
 * since a condition evaluates before any {@code @ConfigurationProperties} bean, including this one,
 * exists. {@link #bootstrapSchema()} is the one defaulting accessor callers should use instead of
 * reading {@link #jdbc} directly — it defaults to {@code true} when unset.
 */
@ConfigurationProperties(prefix = "nessy")
public record NessyProperties(
    String provider, Anthropic anthropic, OpenAi openai, String defaultModel, Jdbc jdbc) {

  /**
   * Whether the JDBC store should bootstrap its own schema. Defaults to {@code true} when unset.
   */
  public boolean bootstrapSchema() {
    return jdbc == null || jdbc.bootstrapSchema() == null || jdbc.bootstrapSchema();
  }

  /** {@code nessy.anthropic.*} — credentials for {@code AnthropicModelProvider}. */
  public record Anthropic(String apiKey, String baseUrl) {}

  /** {@code nessy.openai.*} — credentials for {@code OpenAiModelProvider}. */
  public record OpenAi(String apiKey, String baseUrl) {}

  /**
   * {@code nessy.jdbc.*} — persistence toggles consumed by the persistence autoconfiguration.
   *
   * <p>{@code dialect} mirrors {@code enabled}/{@code bootstrapSchema}'s stance: a plain {@link
   * String} here, not {@code org.jwcarman.nessy.store.jdbc.JdbcDialect} — this record loads
   * unconditionally (every {@code nessy.*} property binds through it, JDBC or not), while that enum
   * lives in the optional {@code nessy-store-jdbc} dependency {@link
   * JdbcPersistenceAutoConfiguration} alone is gated on via {@code @ConditionalOnClass}. A
   * classpath without that module must still be able to load this class; {@code
   * JdbcPersistenceAutoConfiguration} (which only exists on such a classpath in the first place) is
   * where the string turns into the enum — see its {@code nessy.jdbc.dialect} override (design §2:
   * {@code postgres|mysql|mariadb|sqlserver|oracle}, unset means resolve).
   */
  public record Jdbc(Boolean enabled, Boolean bootstrapSchema, String dialect) {}
}
