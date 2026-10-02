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
package org.jwcarman.nessy.spring.boot.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.prompt.PromptTemplateFactory;
import org.jwcarman.nessy.prompt.PromptVariables;
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
 * once, from every {@link PromptVariables} bean, in order, and then the {@code Environment} -- so
 * {@code ${app.persona}} in the prompt is whatever the properties say. An application that declares
 * its own {@link SystemPrompt} keeps it.
 *
 * <p>Rendered once because a system prompt is fixed for the life of a harness: it is the head of
 * every request, and what varies by agent or by the moment belongs in a state or ambient source. A
 * hole nothing fills therefore fails the application's startup.
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
  @ConditionalOnMissingBean(SystemPrompt.class)
  public SystemPrompt nessySystemPrompt(
      PromptTemplateFactory engine,
      NessyProperties properties,
      ObjectProvider<PromptVariables> sources,
      Environment environment) {
    List<PromptVariables> ordered = new ArrayList<>(sources.orderedStream().toList());
    ordered.add(name -> Optional.ofNullable(environment.getProperty(name)));
    return TemplatedSystemPrompt.render(
        engine.compile(properties.resolveSystemPrompt()), PromptVariables.firstOf(ordered));
  }
}
