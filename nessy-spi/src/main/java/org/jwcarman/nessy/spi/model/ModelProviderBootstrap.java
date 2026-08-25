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
package org.jwcarman.nessy.spi.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A provider that builds itself from the environment, discovered through {@link
 * java.util.ServiceLoader}.
 *
 * <p>A provider module implements this interface only when <strong>the presence of its credentials
 * in the environment signals intent to use it</strong>. {@code ANTHROPIC_API_KEY} in an environment
 * means someone meant to talk to Anthropic. Ambient AWS credentials mean someone once deployed
 * something to AWS. The test is not "can this provider be built from the environment" — it is "does
 * the credential's presence mean the user chose this provider". A provider whose credentials are
 * ambient does not implement this interface; its users construct it explicitly. Implementing the
 * interface <em>is</em> the declaration: there is no way to register for discovery while asking not
 * to be discovered, so there is no flag to set wrong.
 *
 * <p>Registration is the standard services file, {@code
 * META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap}, in the provider module's
 * main resources. Implementations are {@code public final} with a public no-arg constructor,
 * stateless, and cheap to construct — {@code ServiceLoader} instantiates them on every discovery.
 *
 * <p>Discovery never depends on {@code ServiceLoader}'s iteration order, which is
 * classpath-dependent: two providers that both bootstrap is an error resolved by {@code
 * NESSY_PROVIDER}, never by position.
 */
public interface ModelProviderBootstrap {

  /**
   * The vocabulary token {@code NESSY_PROVIDER} accepts for this provider, e.g. {@code
   * "anthropic"}. Lowercase, non-blank, and unique across every registration on a classpath — a
   * duplicate is a configuration error and fails at discovery.
   */
  String name();

  /**
   * Every environment variable {@link #bootstrap} reads, optional ones included, so a
   * no-credentials failure can say exactly what was checked.
   */
  Set<String> environmentVariables();

  /** The model id used when {@code NESSY_MODEL} is unset or blank. */
  String defaultModelId();

  /**
   * A gateway built from {@code env}, or empty when — and only when — this provider's credentials
   * are absent from it.
   *
   * <p>Reads only {@code env}, never {@link System#getenv()}, so the choice discovery makes from
   * the map it was handed is the choice that gets built. Must not throw for absent credentials. May
   * throw for present-but-malformed configuration: a present key is intent, and intent that cannot
   * be honoured is an error, not a silent skip.
   *
   * @throws NullPointerException if {@code env} is null
   */
  Optional<ModelProvider> bootstrap(Map<String, String> env);
}
