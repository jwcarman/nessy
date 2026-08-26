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

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.testing.ScriptedModel;

/**
 * The starter's own registered {@link ModelProviderBootstrap} — the only way to exercise the
 * DEFAULT path, where an application declares no {@link Model} bean and the starter's discovery
 * runs for real through {@code ServiceLoader}. Registered in this module's test services file.
 *
 * <p><b>Why a static toggle.</b> Discovery reads the real {@code System.getenv()}, which a test
 * cannot set, and this module uses no mocking library (design of record). One registered class
 * therefore has to serve both halves of the default path — credentials absent, and credentials
 * present — and a static flag is the honest way to say so. {@link #supplying()} returns a handle
 * that restores the previous value on close, so a test that flips it cannot leak into the next one.
 *
 * <p>Default OFF, so every other test in this module sees the same "no credentials" environment it
 * saw before this fixture existed.
 */
public final class StarterTestBootstrap implements ModelProviderBootstrap {

  private static final AtomicBoolean SUPPLYING = new AtomicBoolean(false);

  static final String MODEL_ID = "starter-test-model";
  static final String PROVIDER_NAME = "starter-test";
  static final String ENV_VAR = "STARTER_TEST_KEY";

  /** Turns this bootstrap on for one test; closing restores whatever it was before. */
  static AutoCloseable supplying() {
    boolean previous = SUPPLYING.getAndSet(true);
    return () -> SUPPLYING.set(previous);
  }

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(ENV_VAR);
  }

  @Override
  public String defaultModelId() {
    return MODEL_ID;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    return SUPPLYING.get() ? Optional.of(new TestGateway()) : Optional.empty();
  }

  /** Records that it was closed, so a test can prove the container released the gateway. */
  static final class TestGateway implements ModelProvider {

    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public Model model(String id) {
      return new IdentifiedModel(id);
    }

    @Override
    public void close() {
      closed.set(true);
    }

    boolean isClosed() {
      return closed.get();
    }
  }

  /**
   * A scripted model that reports the id it was BOUND to rather than {@code ScriptedModel}'s own
   * fixed {@code "scripted"} — so a test can tell a model that came from discovery (carrying {@link
   * #MODEL_ID}, or whatever {@code NESSY_MODEL} overrode it with) from one that came from anywhere
   * else. That distinction is the whole point of the default-path tests, and asserting it against a
   * hard-coded id would prove nothing.
   */
  private record IdentifiedModel(String id, ScriptedModel delegate) implements Model {

    private IdentifiedModel(String id) {
      this(id, ScriptedModel.script(s -> s.text("nothing to do").endTurn()));
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String provider() {
      return PROVIDER_NAME;
    }

    @Override
    public Set<Capability> capabilities() {
      return delegate.capabilities();
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      return delegate.stream(request);
    }
  }
}
