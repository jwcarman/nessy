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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * A bootstrap that claims one environment variable and, when it is present, hands back a provider
 * whose models carry their id and nothing else. Which bootstrap won is read off {@code
 * Selection.providerName()}; which model id was resolved is read off {@code Model#id()} — the same
 * two observables the real providers offer, with no SDK behind them.
 */
final class FakeBootstrap implements ModelProviderBootstrap {

  private final String name;
  private final String environmentVariable;
  private final String defaultModelId;
  private final boolean throwsOnPresentKey;
  private final boolean nullVariables;
  private FakeProvider lastProvider;

  FakeBootstrap(String name, String environmentVariable, String defaultModelId) {
    this(name, environmentVariable, defaultModelId, false, false);
  }

  private FakeBootstrap(
      String name,
      String environmentVariable,
      String defaultModelId,
      boolean throwsOnPresentKey,
      boolean nullVariables) {
    this.name = name;
    this.environmentVariable = environmentVariable;
    this.defaultModelId = defaultModelId;
    this.throwsOnPresentKey = throwsOnPresentKey;
    this.nullVariables = nullVariables;
  }

  /** The malformed-configuration case: a present key it cannot honour. */
  static FakeBootstrap throwingOnPresentKey(String name, String environmentVariable) {
    return new FakeBootstrap(name, environmentVariable, name + "-default", true, false);
  }

  /** The SPI-contract-violating case: {@code environmentVariables()} returns null. */
  static FakeBootstrap withNullVariables(String name) {
    return new FakeBootstrap(name, "UNUSED_KEY", name + "-default", false, true);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Set<String> environmentVariables() {
    return nullVariables ? null : Set.of(environmentVariable);
  }

  @Override
  public String defaultModelId() {
    return defaultModelId;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    if (!env.containsKey(environmentVariable)) {
      return Optional.empty();
    }
    if (throwsOnPresentKey) {
      throw new IllegalArgumentException(name + ": " + environmentVariable + " is malformed");
    }
    FakeProvider provider = new FakeProvider(name, new AtomicBoolean());
    lastProvider = provider;
    return Optional.of(provider);
  }

  /**
   * The gateway this bootstrap handed back last, so a test can ask whether discovery closed it.
   * Bootstrapping BUILDS a gateway, and only one candidate can win — the losers have to be closed
   * by whoever built them, which is discovery.
   */
  FakeProvider lastProvider() {
    return lastProvider;
  }

  record FakeProvider(String providerName, AtomicBoolean closed) implements ModelProvider {

    @Override
    public Model model(String id) {
      return new FakeModel(id);
    }

    @Override
    public String name() {
      return providerName;
    }

    @Override
    public void close() {
      closed.set(true);
    }

    boolean isClosed() {
      return closed.get();
    }
  }

  private record FakeModel(String id) implements Model {

    @Override
    public ModelStream stream(ModelRequest request) {
      throw new UnsupportedOperationException("discovery tests never stream");
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public String provider() {
      return "test";
    }
  }
}
