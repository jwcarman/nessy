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
import java.util.stream.Stream;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>Registrations whose {@code name()} breaks the SPI contract — blank, non-lowercase, or a
 * duplicate — fail at discovery, like duplicates.
 */
public final class ModelDiscovery {

  private static final Logger LOG = LoggerFactory.getLogger(ModelDiscovery.class);

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
   *
   * <p>Registrations are loaded through {@link ServiceLoader#load(Class)}, i.e. the thread context
   * class loader — the JDK's default, and the one that finds provider jars in a container where
   * this module sits in a shared library and the providers in the application.
   */
  public static Selection select() {
    return select(System.getenv());
  }

  /** The offline seam for the environment: real registrations, caller-supplied {@code env}. */
  static Selection select(Map<String, String> env) {
    return select(env, ServiceLoader.load(ModelProviderBootstrap.class));
  }

  /** The offline seam for {@link #fromEnv()}: real registrations, caller-supplied {@code env}. */
  static Model fromEnv(Map<String, String> env) {
    return select(env).model();
  }

  /** The offline seam for both: caller-supplied {@code env} and registrations. */
  static Model fromEnv(Map<String, String> env, Iterable<ModelProviderBootstrap> bootstraps) {
    return select(env, bootstraps).model();
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
    // Bootstrapping is not free: every candidate that applies BUILDS a gateway — an SDK client, a
    // connection pool, threads — and only one of them can win. Everything from here to the return
    // is about making sure the losers are closed (fix round, 2026-08-26): before this, a two-key
    // environment leaked a whole gateway on every successful call, and leaked BOTH on the tiebreak
    // throw, where nothing was ever returned to close.
    var candidates = new ArrayList<Candidate>();
    for (var bootstrap : registered) {
      bootstrap.bootstrap(env).ifPresent(p -> candidates.add(new Candidate(bootstrap, p)));
    }
    if (candidates.isEmpty()) {
      throw noCredentials(registered);
    }
    Candidate chosen;
    if (candidates.size() == 1) {
      chosen = candidates.get(0);
    } else {
      try {
        chosen = tiebreak(env.get(NESSY_PROVIDER_ENV_VAR), candidates);
      } catch (RuntimeException e) {
        // Nobody won, so nobody is returned, so nobody else can close these. The ambiguity is the
        // caller's to fix; the gateways are ours to release before we say so.
        closeAll(candidates, e);
        throw e;
      }
    }
    closeAllBut(chosen, candidates);
    var override = env.get(NESSY_MODEL_ENV_VAR);
    var modelId =
        override != null && !override.isBlank() ? override : chosen.bootstrap().defaultModelId();
    return new Selection(
        chosen.provider(),
        chosen.provider().model(org.jwcarman.nessy.api.model.ModelId.of(modelId)),
        chosen.bootstrap().name());
  }

  /**
   * Closes every candidate this call built except the winner, whose gateway rides out on the {@link
   * Selection} for its caller to close. A gateway that throws on close is suppressed onto {@code
   * failure} rather than allowed to mask it — losing a selection because a gateway nobody asked for
   * misbehaved on the way out would be the wrong trade.
   */
  private static void closeAllBut(Candidate chosen, List<Candidate> candidates) {
    for (var candidate : candidates) {
      if (candidate != chosen) {
        closeQuietly(candidate);
      }
    }
  }

  /** Closes every candidate, suppressing each failure onto the exception about to be thrown. */
  private static void closeAll(List<Candidate> candidates, RuntimeException failure) {
    for (var candidate : candidates) {
      try {
        release(candidate.provider());
      } catch (Exception e) {
        failure.addSuppressed(e);
      }
    }
  }

  /**
   * Releases a gateway, if it holds anything to release.
   *
   * <p>{@link ModelProvider} is not itself closeable — asking for a model is all the SPI promises —
   * but every adapter in this repository wraps a vendor HTTP client and implements {@link
   * AutoCloseable} to let it go. Discovery builds several gateways and keeps one, so the losers
   * would leak their connection pools if nobody looked. Testing the instance rather than widening
   * the SPI keeps a decision about resource ownership out of an interface that has no opinion on
   * it.
   */
  private static void release(ModelProvider provider) throws Exception {
    if (provider instanceof AutoCloseable closeable) {
      closeable.close();
    }
  }

  /**
   * Closes one losing gateway, logging rather than throwing: this runs on the success path, and a
   * discarded gateway's teardown failure must not cost the caller the selection it asked for.
   */
  private static void closeQuietly(Candidate candidate) {
    try {
      release(candidate.provider());
    } catch (Exception e) {
      LOG.warn(
          "the '{}' provider was not chosen and threw while being closed; ignored",
          candidate.bootstrap().name(),
          e);
    }
  }

  /** Materialises the registrations and rejects a duplicated name. */
  private static List<ModelProviderBootstrap> registered(
      Iterable<ModelProviderBootstrap> bootstraps) {
    var byName = new HashMap<String, ModelProviderBootstrap>();
    var all = new ArrayList<ModelProviderBootstrap>();
    for (var bootstrap : bootstraps) {
      var name = bootstrap.name();
      if (name == null || name.isBlank()) {
        throw new IllegalStateException(
            "model provider bootstrap " + bootstrap.getClass().getName() + " has a blank name()");
      }
      if (!name.equals(name.toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException(
            "model provider bootstrap "
                + bootstrap.getClass().getName()
                + " has a non-lowercase name() '"
                + name
                + "'");
      }
      Objects.requireNonNull(
          bootstrap.environmentVariables(),
          () -> bootstrap.getClass().getName() + " returned null from environmentVariables()");
      var previous = byName.putIfAbsent(name, bootstrap);
      if (previous != null) {
        var names =
            Stream.of(previous.getClass().getName(), bootstrap.getClass().getName())
                .sorted()
                .toList();
        throw new IllegalStateException(
            "two model provider bootstraps share the name '"
                + name
                + "': "
                + names.get(0)
                + " and "
                + names.get(1));
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
   * model().id()} — the {@code provider} gateway it came from, and the winning provider's
   * registered {@link ModelProviderBootstrap#name()}, so a banner or a log line can show what was
   * picked without re-deriving it via {@code instanceof}.
   *
   * <p><b>Closeable, and the reason the gateway is a component at all</b> (ruled 2026-08-26).
   * Discovery BUILDS a gateway — an SDK client, its connection pool, its threads — and the bound
   * model handle it hands back has no way to release any of it. So the selection carries the
   * gateway and closes it, and an application that means to shut down cleanly writes {@code try
   * (var selection = ModelDiscovery.select())}. Closing invalidates {@link #model()}.
   *
   * <p>{@link #fromEnv()} keeps its shape — a bare {@link Model}, nothing to close — and therefore
   * keeps leaking the gateway for the life of the process. That is right for a CLI and for a
   * process that builds exactly one, which is what that door is for; anything longer-lived should
   * use {@link #select()}.
   */
  public record Selection(ModelProvider provider, Model model, String providerName)
      implements AutoCloseable {

    public Selection {
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(model, "model must not be null");
      Objects.requireNonNull(providerName, "providerName must not be null");
    }

    /**
     * Closes the gateway this selection came from; idempotent, as every gateway's close is.
     *
     * <p>Declares no checked exception, though {@link AutoCloseable#close()} allows one: a
     * selection is held in try-with-resources by ordinary application code, and making every such
     * block catch {@code Exception} would be a tax paid by everyone for a failure no caller can do
     * anything about. A gateway that cannot let go of its client is a bug, so it surfaces as one.
     */
    @Override
    public void close() {
      try {
        release(provider);
      } catch (Exception e) {
        throw new IllegalStateException("the '" + providerName + "' gateway failed to close", e);
      }
    }
  }
}
