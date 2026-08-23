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

import java.util.Set;

/**
 * A bound handle to one model at one vendor.
 *
 * <p>A flyweight over its {@link ModelProvider} gateway's shared client — cheap to create, safe to
 * share, the thing a harness actually consumes. Where the gateway answers for a whole vendor
 * lineup, a {@code Model} answers only for the one id it was bound to: its {@link #capabilities()}
 * are honest per-model facts, not a vendor-wide guess.
 */
public interface Model {

  /**
   * Starts one turn. The caller iterates the returned stream and must close it.
   *
   * <p>Blocking by design: on virtual threads that is cheaper and far more readable than a callback
   * protocol.
   */
  ModelStream stream(ModelRequest request);

  /** What this model can actually do. See {@link Capability}. */
  Set<Capability> capabilities();

  /** This model's id at its vendor — {@code "claude-opus-5"} — for banners and logs. */
  String id();
}
