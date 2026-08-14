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

/**
 * Property key {@link JdbcPersistenceAutoConfiguration}'s {@code @ConditionalOnProperty} names —
 * hoisted out of the annotation's raw string literal, mirroring {@link ProviderProperties}' pattern
 * for the provider-selection keys, so the one place the literal matters (the condition) and any
 * javadoc or test that needs to refer to it by name can never independently drift.
 */
final class JdbcProperties {

  static final String JDBC_ENABLED_PROPERTY = "nessy.jdbc.enabled";

  private JdbcProperties() {}
}
