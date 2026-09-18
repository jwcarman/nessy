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
package org.jwcarman.nessy.spring.boot.narration;

import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.narration.odyssey.AgentStreams;
import org.jwcarman.nessy.narration.odyssey.OdysseyNarrator;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.jwcarman.odyssey.autoconfigure.OdysseyAutoConfiguration;
import org.jwcarman.odyssey.core.Odyssey;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * When an {@link Odyssey} is there, the engine narrates through it.
 *
 * <p>Ordered after Odyssey's own auto-configuration, so the {@code Odyssey} bean exists to be
 * conditioned on, and before Nessy's, whose silent narrator stands down for any {@link
 * AgentEventListener} declared ahead of it. An application that declares its own narrator keeps it;
 * this one only fills the gap.
 */
@AutoConfiguration(after = OdysseyAutoConfiguration.class, before = NessyAutoConfiguration.class)
@ConditionalOnClass(Odyssey.class)
@ConditionalOnBean(Odyssey.class)
@EnableConfigurationProperties(OdysseyNarrationProperties.class)
public class OdysseyNarrationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public AgentStreams nessyAgentStreams(Odyssey odyssey, OdysseyNarrationProperties properties) {
    return new AgentStreams(odyssey, properties.ttl());
  }

  @Bean
  @ConditionalOnMissingBean
  public OdysseyNarrator nessyOdysseyNarrator(AgentStreams streams) {
    return new OdysseyNarrator(streams);
  }
}
