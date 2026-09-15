package org.jwcarman.nessy.spring.boot.prompt;

import org.jwcarman.nessy.prompt.PromptTemplateFactory;
import org.jwcarman.nessy.prompt.mustache.MustachePromptTemplateFactory;
import org.jwcarman.nessy.prompt.spring.SpringPromptTemplateFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * A {@link PromptTemplateFactory} bean for the engine {@code nessy.prompt.engine} names -- {@code
 * spring} (the default: {@code ${name}} with {@code :default}) or {@code mustache} -- when that
 * engine is on the classpath. An application that declares its own keeps it.
 *
 * <p>The engine classes are named as strings on the conditions and never appear in a signature, so
 * this class loads with either engine absent.
 */
@AutoConfiguration
@ConditionalOnClass(PromptTemplateFactory.class)
public class PromptEngineAutoConfiguration {

  static final String ENGINE = "nessy.prompt.engine";

  @Bean
  @ConditionalOnClass(name = "org.jwcarman.nessy.prompt.spring.SpringPromptTemplateFactory")
  @ConditionalOnProperty(name = ENGINE, havingValue = "spring", matchIfMissing = true)
  @ConditionalOnMissingBean(PromptTemplateFactory.class)
  public PromptTemplateFactory nessySpringPromptTemplates() {
    return new SpringPromptTemplateFactory();
  }

  @Bean
  @ConditionalOnClass(name = "org.jwcarman.nessy.prompt.mustache.MustachePromptTemplateFactory")
  @ConditionalOnProperty(name = ENGINE, havingValue = "mustache")
  @ConditionalOnMissingBean(PromptTemplateFactory.class)
  public PromptTemplateFactory nessyMustachePromptTemplates() {
    return new MustachePromptTemplateFactory();
  }
}
