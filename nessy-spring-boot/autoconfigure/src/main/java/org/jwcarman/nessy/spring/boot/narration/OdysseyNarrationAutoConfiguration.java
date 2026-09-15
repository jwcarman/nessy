package org.jwcarman.nessy.spring.boot.narration;

import org.jwcarman.nessy.narration.odyssey.AgentStreams;
import org.jwcarman.nessy.narration.odyssey.OdysseyNarrator;
import org.jwcarman.nessy.spi.narration.Narrator;
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
 * conditioned on, and before Nessy's, whose silent narrator stands down for any {@link Narrator}
 * declared ahead of it. An application that declares its own narrator keeps it; this one only fills
 * the gap.
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
  @ConditionalOnMissingBean(Narrator.class)
  public OdysseyNarrator nessyOdysseyNarrator(AgentStreams streams) {
    return new OdysseyNarrator(streams);
  }
}
