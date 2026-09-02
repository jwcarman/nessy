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
package org.jwcarman.nessy.model.discovery;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/** Registered in this module's test services file; proves the ServiceLoader path end to end. */
public final class RegisteredFakeBootstrap implements ModelProviderBootstrap {

  static final String ENV_VAR = "REGISTERED_FAKE_KEY";

  private final FakeBootstrap delegate =
      new FakeBootstrap("registered", ENV_VAR, "registered-default");

  @Override
  public String name() {
    return delegate.name();
  }

  @Override
  public Set<String> environmentVariables() {
    return delegate.environmentVariables();
  }

  @Override
  public String defaultModelId() {
    return delegate.defaultModelId();
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    return delegate.bootstrap(env);
  }
}
