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
package org.jwcarman.nessy.api;

import java.time.Duration;

/**
 * How this agent type infers: what to call, what to send, how long to wait, how hard to try.
 *
 * <p>The binding for the inference effect, and the shape {@code ToolConfig} mirrors for a tool
 * call. Everything here has a default, taken from the factory where the application configured one
 * once.
 */
public interface InferenceConfig {

  /**
   * Which of the factory's providers answers. Defaults to the factory's; an agent type that names
   * none and a factory with no default fail when the harness is built.
   */
  InferenceConfig provider(ProviderId id);

  /** {@link #provider(ProviderId)}, by name. */
  default InferenceConfig provider(String id) {
    return provider(ProviderId.of(id));
  }

  /** Which model. Defaults to the factory's; required one way or the other. */
  InferenceConfig model(String modelName);

  /**
   * A setting the vendor understands and the neutral API does not name. The name is prefixed by the
   * adapter that reads it ({@code openai.reasoning.effort}); an adapter ignores every other prefix,
   * so an agent type may carry settings for several vendors at once. Only the names an adapter
   * supports are sent: an unsupported name under its prefix is ignored, with a warning. Repeatable;
   * the last value given for a name wins. Fixed when the harness is built, sent with every request.
   *
   * @throws IllegalArgumentException if either argument is blank
   */
  InferenceConfig property(String name, String value);

  /**
   * {@link #property(String, String)}, with the property declared by the adapter that reads it and
   * the value typed: it is stored as the text {@code property} writes it as.
   */
  default <T> InferenceConfig property(VendorProperty<T> property, T value) {
    return property(property.name(), property.format(value));
  }

  /** How much answer it may have. Zero or unset leaves it to the provider. */
  InferenceConfig maxTokens(int maxTokens);

  /**
   * How the context for the call is built: summaries, the tail, and background. See {@link
   * ContextConfig} for the order those are sent in and what each one means.
   */
  InferenceConfig context(Customizer<ContextConfig> customizer);

  /**
   * How long the agent is willing to wait for an answer, measured from when the work is written
   * down -- queueing included, because queueing is the agent waiting.
   */
  InferenceConfig timeout(Duration timeout);

  /**
   * How hard a failed inference is worth repeating.
   *
   * <p>The one binding most applications widen: a provider's 503 is the canonical retryable
   * failure, and a call that never reached a model changed nothing by being repeated.
   */
  InferenceConfig retryPolicy(RetryPolicy retryPolicy);
}
