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
package org.jwcarman.nessy.testing;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * A model that says exactly what you told it to.
 *
 * <p>This is how the whole loop gets tested without a key, a network, or a nondeterministic remote
 * service that charges per call. It also records every request it received, so tests can assert on
 * what the harness <em>sent</em>, which is usually the more interesting half.
 *
 * <p>Its turn-and-request bookkeeping is synchronized: examples drive on virtual threads, and a
 * concurrent resume racing a park against this same provider must not corrupt {@code nextTurn} or
 * the request log.
 */
public final class ScriptedModelProvider implements ModelProvider {

  private final List<List<ModelEvent>> turns;
  private final List<ModelRequest> requests = new ArrayList<>();
  private int nextTurn;

  ScriptedModelProvider(List<List<ModelEvent>> turns) {
    this.turns = List.copyOf(turns);
  }

  /**
   * Scripts a {@link ScriptedModelProvider}: {@code customizer} fills in a live {@link
   * ScriptedModelProviderConfig}, then this factory turns it into the finished provider. No public
   * {@code build()} survives here; the factory is the only place a {@link
   * ScriptedModelProviderConfig} ever turns into a {@link ScriptedModelProvider} (design of record
   * 2026-08-16 §1).
   */
  public static ScriptedModelProvider script(ScriptedModelProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    ScriptedModelProviderConfig config = new ScriptedModelProviderConfig();
    customizer.customize(config);
    return config.build();
  }

  @Override
  public synchronized ModelStream stream(ModelRequest request) {
    if (nextTurn >= turns.size()) {
      throw new IllegalStateException(
          "script exhausted: the harness asked for turn " + (nextTurn + 1) + " of " + turns.size());
    }
    requests.add(request);
    Iterator<ModelEvent> events = turns.get(nextTurn++).iterator();
    return new ModelStream() {

      private boolean iterated;

      @Override
      public Iterator<ModelEvent> iterator() {
        // A second pass over an already-advanced iterator would silently look
        // like an empty turn. This module exists to fail loudly instead.
        if (iterated) {
          throw new IllegalStateException(
              "this ModelStream has already been iterated; a stream replays one turn exactly once");
        }
        iterated = true;
        return events;
      }

      @Override
      public void close() {
        // Nothing to release: the script is already in memory.
      }
    };
  }

  @Override
  public Set<Capability> capabilities() {
    return Set.of();
  }

  @Override
  public String name() {
    return "Scripted";
  }

  /** A snapshot of every request this provider was handed, oldest first. */
  public synchronized List<ModelRequest> requests() {
    return List.copyOf(requests);
  }
}
