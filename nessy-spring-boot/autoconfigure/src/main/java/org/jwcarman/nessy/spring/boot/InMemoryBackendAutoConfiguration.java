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

import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryLeases;
import org.jwcarman.nessy.backend.lease.Leases;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The direct door's fallback, for an application with no durable backend of its own.
 *
 * <p>Ordered after {@link JdbcBackendAutoConfiguration} so a {@code DataSource} wins when there is
 * one: an application with both a database and this module on its classpath gets the durable
 * backend, and only an application with neither -- a CLI, a test -- falls back to this.
 *
 * <p>Contributes only a {@link DirectBackend}. There is no in-memory {@code QueuedBackend}: nothing
 * in this module implements an in-memory {@code Agents} or {@code Effects}, so the queued door
 * stays undeclared until a durable backend supplies one.
 */
@AutoConfiguration(after = {NessyAutoConfiguration.class, JdbcBackendAutoConfiguration.class})
@ConditionalOnClass(InMemoryDirectBackend.class)
public class InMemoryBackendAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DirectBackend directBackend(CodecFactory codecs) {
    return new InMemoryDirectBackend(codecs);
  }

  /**
   * The same opportunistic exclusion the JDBC backend publishes, for an application with no
   * database -- which is what lets a summariser run at all without one.
   *
   * <p>Held in this process and nowhere else, so it excludes nothing outside it. That is the same
   * bargain {@link InMemoryDirectBackend} makes and it is the reason both back off when a JDBC
   * backend is present.
   */
  @Bean
  @ConditionalOnMissingBean
  public Leases leases() {
    return new InMemoryLeases();
  }
}
