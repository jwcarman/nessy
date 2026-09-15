package org.jwcarman.nessy.spring.boot.prompt;

import org.jwcarman.nessy.prompt.PromptTemplateFactory;
import org.jwcarman.nessy.prompt.mustache.MustachePromptTemplateFactory;
import org.jwcarman.nessy.prompt.spring.SpringPromptTemplateFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A {@link PromptTemplateFactory} bean for the engine {@code nessy.prompt.engine} names -- {@code
 * spring} (the default: {@code ${name}} with {@code :default}) or {@code mustache} -- when that
 * engine is on the classpath. An application that declares its own keeps it.
 */
@AutoConfiguration
@ConditionalOnClass(PromptTemplateFactory.class)
public class PromptEngineAutoConfiguration {

  static final String ENGINE = "nessy.prompt.engine";

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(SpringPromptTemplateFactory.class)
  @ConditionalOnProperty(name = ENGINE, havingValue = "spring", matchIfMissing = true)
  static class Spring {
    @Bean
    @ConditionalOnMissingBean(PromptTemplateFactory.class)
    PromptTemplateFactory nessySpringPromptTemplates() {
      return new SpringPromptTemplateFactory();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MustachePromptTemplateFactory.class)
  @ConditionalOnProperty(name = ENGINE, havingValue = "mustache")
  static class Mustache {
    @Bean
    @ConditionalOnMissingBean(PromptTemplateFactory.class)
    PromptTemplateFactory nessyMustachePromptTemplates() {
      return new MustachePromptTemplateFactory();
    }
  }
}
