package org.jwcarman.nessy.narration.odyssey;

import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * The one bean Substrate needs and nothing supplies.
 *
 * <p>Substrate's journal factory is conditional on a {@link CodecFactory} bean, and no released
 * Substrate or Odyssey artifact declares one -- so without this, adding a backend gives a context
 * with no journal and, quietly, no Odyssey. Built from the application's mapper, and only when the
 * application has not built its own. Ordered before Substrate's auto-configuration because its
 * conditions are evaluated as it registers.
 */
@AutoConfiguration(
    beforeName = "org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration")
@ConditionalOnClass(name = "org.jwcarman.substrate.journal.JournalFactory")
@ConditionalOnBean(ObjectMapper.class)
public class SubstrateCodecAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public CodecFactory nessySubstrateCodecs(ObjectMapper mapper) {
    return new JacksonCodecFactory(mapper);
  }
}
