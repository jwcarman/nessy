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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The provider choice IS the switch: {@link WatchmanConfiguration#scripted()} exists only when
 * {@code nessy.provider} names it, never on a property of its own.
 *
 * <p>Every bean definition is made lazy so the context never has to satisfy {@code
 * WatchmanConfiguration}'s other beans (a database, a queued harness factory) that a real
 * application supplies but this test does not -- {@code getBeansOfType(InferenceProvider.class)}
 * only forces the one bean whose type could match.
 */
@DisplayName("Watchman's own scripted provider bean")
class WatchmanConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              context ->
                  context.addBeanFactoryPostProcessor(
                      new LazyInitializationBeanFactoryPostProcessor()))
          .withUserConfiguration(WatchmanConfiguration.class);

  @Test
  @DisplayName("does not exist when nothing names nessy.provider as scripted")
  void no_scripted_bean_with_no_provider_named() {
    runner.run(context -> assertThat(context.getBeansOfType(InferenceProvider.class)).isEmpty());
  }
}
