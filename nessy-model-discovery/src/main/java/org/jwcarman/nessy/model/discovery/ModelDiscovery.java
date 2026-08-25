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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * The provider follows the classpath (model-discovery design, 2026-08-25): every {@link
 * ModelProviderBootstrap} a provider module registered is loaded through {@link ServiceLoader},
 * each is asked to bootstrap from the environment, and the one that applies wins. This module
 * depends on no provider module; an application chooses which SDKs ride its classpath by choosing
 * which provider jars it adds, then configures the one it chose with its key.
 *
 * <p>Three outcomes and nothing in between:
 *
 * <ul>
 *   <li><strong>None</strong> bootstrapped — {@link IllegalStateException} naming, per registered
 *       provider, its name and the variables it reads. Only providers actually on the classpath are
 *       named; a user with one jar is told about one variable.
 *   <li><strong>One</strong> bootstrapped — chosen, silently. {@code NESSY_PROVIDER} is ignored
 *       here whatever it says: it exists to break ties, and one candidate has none.
 *   <li><strong>Two or more</strong> bootstrapped — {@code NESSY_PROVIDER} (case-insensitive)
 *       naming one of them chooses it silently; anything else is an {@link IllegalStateException}
 *       naming every candidate. Two providers that both bootstrap means the application
 *       deliberately shipped two provider jars and set two keys; that ambiguity is a configuration
 *       error, and a log line nobody reads is the wrong place to resolve it.
 * </ul>
 *
 * <p>The model id is {@code NESSY_MODEL} when set and non-blank, otherwise the winner's {@link
 * ModelProviderBootstrap#defaultModelId()} — applied here, once, so the precedence rule has one
 * owner rather than one copy per provider.
 *
 * <p>Nothing here depends on {@code ServiceLoader}'s iteration order, which varies with classpath
 * layout: messages list providers sorted by name, and no outcome is decided by position.
 */
public final class ModelDiscovery {

  static final String NESSY_PROVIDER_ENV_VAR = "NESSY_PROVIDER";
  static final String NESSY_MODEL_ENV_VAR = "NESSY_MODEL";

  private ModelDiscovery() {}

  /** The minimal door: a bound {@link Model} from the real process environment. */
  public static Model fromEnv() {
    return select().model();
  }

  /**
   * The door for applications that also want to show what was chosen: the bound model plus the
   * winning provider's registered name, from the real process environment.
   */
  public static Selection select() {
    return select(System.getenv());
  }

  /** The offline seam for the environment: real registrations, caller-supplied {@code env}. */
  static Selection select(Map<String, String> env) {
    return select(env, ServiceLoader.load(ModelProviderBootstrap.class));
  }

  /** The offline seam for both: caller-supplied {@code env} and caller-supplied registrations. */
  static Selection select(Map<String, String> env, Iterable<ModelProviderBootstrap> bootstraps) {
    Objects.requireNonNull(env, "env must not be null");
    Objects.requireNonNull(bootstraps, "bootstraps must not be null");
    var registered = registered(bootstraps);
    if (registered.isEmpty()) {
      throw new IllegalStateException(
          "no model provider modules are on the classpath: add one of nessy-model-anthropic,"
              + " nessy-model-openai, or nessy-model-gemini (or construct a provider directly)");
    }
    var candidates = new ArrayList<Candidate>();
    for (var bootstrap : registered) {
      bootstrap.bootstrap(env).ifPresent(p -> candidates.add(new Candidate(bootstrap, p)));
    }
    var chosen =
        switch (candidates.size()) {
          case 0 -> throw noCredentials(registered);
          case 1 -> candidates.get(0);
          default -> tiebreak(env.get(NESSY_PROVIDER_ENV_VAR), candidates);
        };
    var override = env.get(NESSY_MODEL_ENV_VAR);
    var modelId =
        override != null && !override.isBlank() ? override : chosen.bootstrap().defaultModelId();
    return new Selection(chosen.provider().model(modelId), chosen.bootstrap().name());
  }

  /** Materialises the registrations and rejects a duplicated name. */
  private static List<ModelProviderBootstrap> registered(
      Iterable<ModelProviderBootstrap> bootstraps) {
    var byName = new HashMap<String, ModelProviderBootstrap>();
    var all = new ArrayList<ModelProviderBootstrap>();
    for (var bootstrap : bootstraps) {
      var previous = byName.putIfAbsent(bootstrap.name(), bootstrap);
      if (previous != null) {
        throw new IllegalStateException(
            "two model provider bootstraps share the name '"
                + bootstrap.name()
                + "': "
                + previous.getClass().getName()
                + " and "
                + bootstrap.getClass().getName());
      }
      all.add(bootstrap);
    }
    return all;
  }

  private static IllegalStateException noCredentials(List<ModelProviderBootstrap> registered) {
    var listing =
        registered.stream()
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .map(b -> b.name() + " " + new TreeSet<>(b.environmentVariables()))
            .collect(Collectors.joining("; "));
    return new IllegalStateException(
        "no model provider credentials found — registered providers and the variables they read: "
            + listing);
  }

  private static Candidate tiebreak(String preference, List<Candidate> candidates) {
    var names =
        candidates.stream()
            .map(c -> c.bootstrap().name())
            .sorted()
            .collect(Collectors.joining(", ", "(", ")"));
    if (preference != null) {
      var wanted = preference.toLowerCase(Locale.ROOT);
      for (var candidate : candidates) {
        if (candidate.bootstrap().name().equals(wanted)) {
          return candidate;
        }
      }
    }
    var suffix =
        preference == null || preference.isBlank()
            ? ""
            : " (" + NESSY_PROVIDER_ENV_VAR + "=" + preference + " names none of them)";
    throw new IllegalStateException(
        "multiple model providers can bootstrap from this environment "
            + names
            + " — set "
            + NESSY_PROVIDER_ENV_VAR
            + " to one of them to choose"
            + suffix);
  }

  private record Candidate(ModelProviderBootstrap bootstrap, ModelProvider provider) {}

  /**
   * What {@link #select()} chose: the bound {@code model} — its id reachable as {@code
   * model().id()} — and the winning provider's registered {@link ModelProviderBootstrap#name()}, so
   * a banner or a log line can show what was picked without re-deriving it via {@code instanceof}.
   */
  public record Selection(Model model, String providerName) {

    public Selection {
      Objects.requireNonNull(model, "model must not be null");
      Objects.requireNonNull(providerName, "providerName must not be null");
    }
  }
}
