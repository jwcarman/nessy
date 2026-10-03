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

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.UsageReports;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.engine.usage.EventUsageReports;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Usage reports over the stored events of whichever doors the application has.
 *
 * <p>Both doors' stores are given, queued first: an agent's story is in one of them, and the
 * projection reads the first that holds it, so a backend whose two doors share tables is never
 * counted twice.
 */
@AutoConfiguration(
    after = {JdbcBackendAutoConfiguration.class, InMemoryBackendAutoConfiguration.class})
public class UsageReportsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public UsageReports nessyUsageReports(
      ObjectProvider<QueuedBackend> queued, ObjectProvider<DirectBackend> direct) {
    List<AgentEvents> stores = new ArrayList<>();
    queued.ifAvailable(backend -> stores.add(backend.events()));
    direct.ifAvailable(backend -> stores.add(backend.events()));
    return new EventUsageReports(stores);
  }
}
