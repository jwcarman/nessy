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
package org.jwcarman.nessy.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NessyWebAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NessyWebAutoConfiguration.class));

  @Test
  void sse_emitter_on_the_classpath_yields_a_turn_runner_bean() {
    runner.run(context -> assertThat(context).hasSingleBean(TurnRunner.class));
  }

  @Test
  void sse_emitter_absent_means_no_turn_runner_bean() {
    runner
        .withClassLoader(new FilteredClassLoader(SseEmitter.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(TurnRunner.class);
            });
  }

  @Test
  void context_propagation_absent_means_no_turn_runner_bean() {
    // TurnRunner's field initializer constructs a ContextSnapshotFactory outright, so a webmvc
    // app that lacks io.micrometer:context-propagation (an optional dependency micrometer-tracing
    // normally pulls in, not spring-webmvc itself) would crash with NoClassDefFoundError the
    // moment Spring tried to instantiate the bean — a webmvc-only gate can't see that missing jar.
    runner
        .withClassLoader(new FilteredClassLoader(ContextSnapshotFactory.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(TurnRunner.class);
            });
  }

  @Test
  void a_user_declared_turn_runner_bean_wins() {
    TurnRunner mine = new TurnRunner();
    runner
        .withBean("mine", TurnRunner.class, () -> mine)
        .run(context -> assertThat(context.getBean(TurnRunner.class)).isSameAs(mine));
  }
}
