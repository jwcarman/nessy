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
 * #jdbc} itself binds to {@code null} when no {@code nessy.jdbc.*} property is set at all, and
 * {@link #jdbcEnabled()} / {@link #bootstrapSchema()} are the defaulting accessors callers should
 * use instead of reading {@link #jdbc} directly — both default to {@code true} when unset.
 */
@ConfigurationProperties(prefix = "nessy")
public record NessyProperties(
    String provider, Anthropic anthropic, OpenAi openai, String defaultModel, Jdbc jdbc) {

  /** Whether JDBC persistence should be wired up. Defaults to {@code true} when unset. */
  public boolean jdbcEnabled() {
    return jdbc == null || jdbc.enabled() == null || jdbc.enabled();
  }

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

  /** {@code nessy.jdbc.*} — persistence toggles consumed by the persistence autoconfiguration. */
  public record Jdbc(Boolean enabled, Boolean bootstrapSchema) {}
}
