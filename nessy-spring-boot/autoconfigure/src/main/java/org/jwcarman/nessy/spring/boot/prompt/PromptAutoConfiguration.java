package org.jwcarman.nessy.spring.boot.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.prompt.PromptTemplateFactory;
import org.jwcarman.nessy.prompt.PromptVariableSource;
import org.jwcarman.nessy.prompt.TemplatedSystemPrompt;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * {@code nessy.system-prompt} (or {@code -file}) as a template, when an engine is there: rendered
 * per agent from every {@link PromptVariableSource} bean, in order, and then the {@code
 * Environment} -- so {@code ${app.persona}} in the prompt is whatever the properties say. An
 * application that declares its own {@link SystemPromptSource} keeps it.
 *
 * <p>Note that Boot resolves {@code ${...}} inside an inline {@code nessy.system-prompt} property
 * when it binds it, before any engine sees it; a prompt file reaches the engine untouched.
 */
@AutoConfiguration(
    after = PromptEngineAutoConfiguration.class,
    before = NessyAutoConfiguration.class)
@ConditionalOnClass(PromptTemplateFactory.class)
@ConditionalOnBean(PromptTemplateFactory.class)
@EnableConfigurationProperties(NessyProperties.class)
public class PromptAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(SystemPromptSource.class)
  public SystemPromptSource nessySystemPrompt(
      PromptTemplateFactory engine,
      NessyProperties properties,
      ObjectProvider<PromptVariableSource> sources,
      Environment environment) {
    List<PromptVariableSource> ordered = new ArrayList<>(sources.orderedStream().toList());
    ordered.add((_, name) -> Optional.ofNullable(environment.getProperty(name)));
    return TemplatedSystemPrompt.of(
        engine.compile(properties.resolveSystemPrompt()), PromptVariableSource.firstOf(ordered));
  }
}
