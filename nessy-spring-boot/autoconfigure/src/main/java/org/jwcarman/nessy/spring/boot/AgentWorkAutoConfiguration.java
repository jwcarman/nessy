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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.engine.work.StoredAgentWork;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * What each agent is doing and which approvals wait on a person, over whichever doors the
 * application has.
 *
 * <p>Built from the backends and not from the harness factories: a factory needs a provider and a
 * model, and a backend is all this reads. Both doors are given, queued first: an agent is read from
 * the first door whose store holds an event for it, as agent stories choose. With no backend there
 * is nothing to read, and no bean.
 */
@AutoConfiguration(
    after = {JdbcBackendAutoConfiguration.class, InMemoryBackendAutoConfiguration.class})
@Conditional(AgentWorkAutoConfiguration.AnyBackend.class)
public class AgentWorkAutoConfiguration {

  /** At least one door's backend is present. */
  static class AnyBackend extends AnyNestedCondition {

    AnyBackend() {
      super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnBean(QueuedBackend.class)
    static class Queued {}

    @ConditionalOnBean(DirectBackend.class)
    static class Direct {}
  }

  @Bean
  @ConditionalOnMissingBean
  public AgentWork nessyAgentWork(
      ObjectProvider<QueuedBackend> queued, ObjectProvider<DirectBackend> direct) {
    Clock clock = Clock.systemUTC();
    List<FirstStoreHoldingWork.Door> doors = new ArrayList<>();
    queued.ifAvailable(
        backend ->
            doors.add(
                new FirstStoreHoldingWork.Door(
                    backend.events(), StoredAgentWork.queued(backend, clock))));
    direct.ifAvailable(
        backend ->
            doors.add(
                new FirstStoreHoldingWork.Door(
                    backend.events(), StoredAgentWork.direct(backend.events(), clock))));
    return new FirstStoreHoldingWork(doors);
  }
}
