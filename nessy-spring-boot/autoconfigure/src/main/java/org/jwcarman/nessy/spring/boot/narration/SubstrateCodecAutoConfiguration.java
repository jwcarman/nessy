package org.jwcarman.nessy.spring.boot.narration;

import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one bean Substrate needs and nothing supplies.
 *
 * <p>Substrate's journal factory is conditional on a {@link CodecFactory} bean, and no released
 * Substrate or Odyssey artifact declares one -- so without this, adding a backend gives a context
 * with no journal and, quietly, no Odyssey. Built from the application's mapper, and only when the
 * application has not built its own. Ordered before Substrate's auto-configuration because its
 * conditions are evaluated as it registers, and after Boot's Jackson so the mapper is the
 * application's.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
    beforeName = "org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration")
@ConditionalOnClass(name = "org.jwcarman.substrate.journal.JournalFactory")
public class SubstrateCodecAutoConfiguration {

  // The application's mapper when Boot has made one by now, a plain one otherwise. Not a
  // condition on the bean: a condition is evaluated as this registers, and Boot's Jackson
  // auto-configuration is only ordered before this, not guaranteed to have won its own conditions.
  @Bean
  @ConditionalOnMissingBean
  public CodecFactory nessySubstrateCodecs(ObjectProvider<ObjectMapper> mappers) {
    return new JacksonCodecFactory(mappers.getIfAvailable(() -> JsonMapper.builder().build()));
  }
}
