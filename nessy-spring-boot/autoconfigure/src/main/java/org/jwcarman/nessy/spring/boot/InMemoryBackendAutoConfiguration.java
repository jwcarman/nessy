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

import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryLeases;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.backend.lease.Leases;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Either door's fallback, for an application with no durable backend of its own.
 *
 * <p>Ordered after {@link JdbcBackendAutoConfiguration} so a {@code DataSource} wins when there is
 * one: an application with both a database and this module on its classpath gets the durable
 * backend, and only an application with neither -- a CLI, a test -- falls back to this.
 *
 * <p><b>Both doors, not just the direct one.</b> The queued door needs an {@code Agents} and an
 * {@code Effects} on top of what the direct one needs, and this module implements both, so an
 * application with no database still gets the door that writes work down and picks it up later.
 * What it does not get is durability: nothing here survives the process, which is what makes this a
 * test's backend rather than a production one.
 */
@AutoConfiguration(after = {NessyAutoConfiguration.class, JdbcBackendAutoConfiguration.class})
@ConditionalOnClass(InMemoryDirectBackend.class)
public class InMemoryBackendAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DirectBackend directBackend(CodecFactory codecs) {
    if (codecs instanceof StorageLayers(CodecFactory values, Codec<byte[]> transform)) {
      return new InMemoryDirectBackend(values, transform);
    }
    return new InMemoryDirectBackend(codecs);
  }

  /**
   * The queued door works with nothing behind it but this process, which is what lets a test drive
   * it without a database. Nothing it writes survives a restart, so it is right for a test and a
   * CLI and wrong for anything that must not lose work; the JDBC backend is ordered ahead for
   * exactly that reason, and this only appears when there is no DataSource to build one from.
   */
  @Bean
  @ConditionalOnMissingBean
  public QueuedBackend queuedBackend(CodecFactory codecs) {
    if (codecs instanceof StorageLayers(CodecFactory values, Codec<byte[]> transform)) {
      return new InMemoryQueuedBackend(values, transform);
    }
    return new InMemoryQueuedBackend(codecs);
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
