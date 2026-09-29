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
package org.jwcarman.nessy.spring.boot.inference;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers every {@code nessy.providers.<id>} preset (lit by a vendor key or an explicit {@code
 * enabled}) and every custom provider as an {@code InferenceProvider} bean named by its id.
 *
 * <p>Replaces the three vendor-specific auto-configurations that used to each contribute at most
 * one bean, backed off by {@code @ConditionalOnMissingBean}: with a registry rather than a single
 * slot, there is nothing to protect, and every application-declared {@code InferenceProvider} bean
 * joins the same registry beside the presets.
 */
@AutoConfiguration
public class InferenceProvidersAutoConfiguration {

  @Bean
  static ProviderRegistrar nessyProviderRegistrar() {
    return new ProviderRegistrar();
  }

  /** Says what will actually answer, before a single turn runs. */
  @Bean
  @ConditionalOnMissingBean
  public InferenceReport nessyInferenceReport(
      ObjectProvider<ResolvedProviders> resolvedProviders, ListableBeanFactory beans) {
    return new InferenceReport(resolvedProviders, beans);
  }
}
