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
package org.jwcarman.nessy.spring.boot;

import java.util.List;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

/**
 * Says what will actually answer, once, at startup.
 *
 * <p><b>Which provider answers is decided by which key happens to be set</b>, and the model name is
 * chosen separately, so the two can disagree. The only symptom is a 404 from a vendor nobody meant
 * to call, or an answer in a style nobody recognises. This is the line that makes it visible before
 * a single turn runs.
 *
 * <p>It warns when more than one provider is configured, because that is the case where what
 * answers is decided by nothing anybody wrote down -- auto-configurations are applied in an order
 * that has no relationship to intent, and the loser is silent.
 *
 * <p>Never the key, obviously. The endpoint is the provider's own to report, and most do not, so
 * this says what it can say for certain: who, which model, and how much they are allowed to write.
 */
final class InferenceReport implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(InferenceReport.class);

  /**
   * The keys that each turn a provider on, and the property is the whole condition -- setting one
   * is how a vendor gets chosen, which is why having two set is worth saying out loud.
   */
  private static final List<String> KEYS =
      List.of("openai.api-key", "anthropic.api-key", "gemini.api-key", "xai.api-key");

  private final ObjectProvider<InferenceProvider> providers;
  private final NessyProperties properties;
  private final Environment environment;

  InferenceReport(
      ObjectProvider<InferenceProvider> providers,
      NessyProperties properties,
      Environment environment) {
    this.providers = providers;
    this.properties = properties;
    this.environment = environment;
  }

  @Override
  public void afterSingletonsInstantiated() {
    InferenceProvider chosen = providers.getIfAvailable();
    if (chosen == null) {
      // Not this class's business to fail: whatever needs a provider will say so far more
      // usefully, naming the bean it wanted.
      log.warn("NESSY INFERENCE: no provider is configured");
      return;
    }
    if (log.isInfoEnabled()) {
      log.info(
          "NESSY INFERENCE: {} answering as model '{}', up to {} tokens",
          chosen.vendor(),
          properties.model(),
          properties.maxTokens());
    }

    // Only ONE provider bean is ever built -- each is conditional on no other existing -- so the
    // one that wins is the one whose auto-configuration happened to run first, and the losers say
    // nothing at all. The keys are what the decision was actually made from, so they are what is
    // worth reporting when there is more than one of them.
    List<String> configured = KEYS.stream().filter(environment::containsProperty).toList();
    if (configured.size() > 1 && log.isWarnEnabled()) {
      log.warn(
          "NESSY INFERENCE: {} provider keys are set ({}), and only one provider is built."
              + " {} won by the order auto-configurations happen to run in, which is nobody's"
              + " decision. Unset the keys you do not mean to use.",
          configured.size(),
          configured,
          chosen.vendor());
    }
  }
}
