package org.jwcarman.nessy.narration.odyssey;

import org.jwcarman.nessy.spi.narration.Narrator;
import org.jwcarman.odyssey.autoconfigure.OdysseyAutoConfiguration;
import org.jwcarman.odyssey.core.Odyssey;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * When an {@link Odyssey} is there, the engine narrates through it.
 *
 * <p>Ordered after Odyssey's own auto-configuration, so the {@code Odyssey} bean exists to be
 * conditioned on, and before Nessy's, whose silent narrator stands down for any {@link Narrator}
 * declared ahead of it. An application that declares its own narrator keeps it; this one only fills
 * the gap.
 */
@AutoConfiguration(
    after = OdysseyAutoConfiguration.class,
    beforeName = "org.jwcarman.nessy.spring.boot.NessyAutoConfiguration")
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
  public OdysseyNarrator nessyOdysseyNarrator(AgentStreams streams, ObjectMapper mapper) {
    return new OdysseyNarrator(streams, mapper);
  }
}
