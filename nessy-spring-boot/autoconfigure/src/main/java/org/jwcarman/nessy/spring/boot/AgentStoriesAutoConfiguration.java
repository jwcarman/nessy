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
import org.jwcarman.nessy.api.AgentStories;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.engine.story.EventAgentStories;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Agent stories over the stored events of whichever doors the application has.
 *
 * <p>Both doors' stores are asked, queued first: an agent's story is in one of them, and the first
 * that holds any of what was asked for answers, so a backend whose two doors share tables gives the
 * same answer either way. With no backend there is nothing to read, and no bean.
 */
@AutoConfiguration(
    after = {JdbcBackendAutoConfiguration.class, InMemoryBackendAutoConfiguration.class})
@Conditional(AgentStoriesAutoConfiguration.AnyBackend.class)
public class AgentStoriesAutoConfiguration {

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
  public AgentStories nessyAgentStories(
      ObjectProvider<QueuedBackend> queued, ObjectProvider<DirectBackend> direct) {
    List<AgentStories> stores = new ArrayList<>();
    queued.ifAvailable(backend -> stores.add(new EventAgentStories(backend.events())));
    direct.ifAvailable(backend -> stores.add(new EventAgentStories(backend.events())));
    return (type, id) ->
        (after, limit) -> {
          List<Narrated> story = List.of();
          for (AgentStories store : stores) {
            story = store.of(type, id).replay(after, limit);
            if (!story.isEmpty()) {
              return story;
            }
          }
          return story;
        };
  }
}
