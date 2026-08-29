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
 * lineup, a {@code Model} answers only for the one id it was bound to: {@link #capabilities()} is
 * WHERE a per-model answer belongs — this interface does not require every implementation to
 * already answer that precisely; a vendor whose lineup shares one capability set today may still
 * answer per-vendor here, as the four vendor implementations' own javadocs say.
 */
public interface Model {

  /**
   * Starts one turn. The caller iterates the returned stream and must close it.
   *
   * <p>Blocking by design: on virtual threads that is cheaper and far more readable than a callback
   * protocol.
   */
  ModelStream stream(ModelRequest request);

  /**
   * What this model is, in one place: its id, its provider, its context window, and what it can do.
   *
   * <p>The single method an implementation must answer. {@link #id()}, {@link #provider()} and
   * {@link #capabilities()} read off it, so nothing has to be kept in step by hand.
   */
  ModelDescription describe();

  /** What this model can actually do. See {@link Capability}. */
  default Set<Capability> capabilities() {
    return describe().capabilities();
  }

  /** This model's id at its vendor — {@code "claude-opus-5"} — for banners and logs. */
  default String id() {
    return describe().id();
  }

  /**
   * The semconv {@code gen_ai.provider.name} value of the vendor this model is bound at: {@code
   * anthropic}, {@code openai}, {@code x_ai}, {@code gcp.gemini}, {@code aws.bedrock}.
   *
   * <p>Telemetry, not a banner — unlike {@link ModelProvider#name()}, which is a human-readable
   * label with a class-name default. This is one of the OpenTelemetry GenAI semantic conventions'
   * pinned values, and it is asked of the MODEL rather than the gateway on purpose: the executor
   * that opens the {@code chat} span holds a bound {@code Model} and never sees a {@link
   * ModelProvider}, and one gateway class can serve several vendors — the OpenAI-compatible gateway
   * answers {@code openai} for an OpenAI key and {@code x_ai} for an xAI one, which only the bound
   * handle can know.
   *
   * <p>No default: every implementation answers, so a new vendor cannot quietly report someone
   * else's name.
   */
  default String provider() {
    return describe().provider();
  }
}
