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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code spring-webmvc} on the classpath is the whole opt-in: a {@link TurnRunner} bean appears the
 * moment {@link SseEmitter} resolves, ready for any {@code @RestController} the application writes
 * itself — this module builds no controller, no endpoint, no route. Identity (which conversations,
 * which URLs, which request/response shapes) stays the application's own call, exactly as {@link
 * org.jwcarman.nessy.autoconfigure.NessyAutoConfiguration}'s razor keeps a {@code Harness}
 * substrate and an {@code AgentBuilder} identity apart.
 */
@AutoConfiguration
@ConditionalOnClass(SseEmitter.class)
public class NessyWebAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  TurnRunner turnRunner() {
    return new TurnRunner();
  }
}
