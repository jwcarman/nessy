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
package org.jwcarman.nessy.engine.harness;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.Embedder;
import org.jwcarman.nessy.api.ExtractorFactory;
import org.jwcarman.nessy.api.HarnessFactory;
import org.jwcarman.nessy.api.Nessy;
import org.jwcarman.nessy.engine.extraction.DefaultExtractorFactory;

/**
 * A {@link Nessy} over one database and one provider.
 *
 * <p>Holds the engine and hands out the three ways of using a model. The harnesses are the engine's
 * own; the extractors are built over the same provider and never touch the database, which is what
 * makes reading a document cost a call and nothing else.
 *
 * <p>Closing this closes the engine. The extractors hold no resources of their own -- a provider
 * belongs to whoever made it -- so there is nothing else to let go of.
 */
public final class DefaultNessy implements Nessy {

  private final DefaultHarnessFactory harnesses;
  private final ExtractorFactory extractors;
  private final Embedder embedder;

  private DefaultNessy(NessyConfig config) {
    // Asked for before anything is built: a missing provider is a wiring mistake, and finding it
    // when the first turn runs is finding it in the wrong place.
    this.extractors =
        new DefaultExtractorFactory(config.requiredProvider(), config.schemas(), config.mapper());
    this.embedder = config.embedder();
    this.harnesses = new DefaultHarnessFactory(config.engine());
  }

  /**
   * Builds one, and everything under it.
   *
   * <p>The database is reached, the schema is applied and the pool is opened here, so this is
   * something an application does once on the way up rather than per use.
   */
  public static Nessy of(Consumer<NessyConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    NessyConfig config = new NessyConfig();
    customizer.accept(config);
    return new DefaultNessy(config);
  }

  @Override
  public HarnessFactory harnesses() {
    return harnesses;
  }

  @Override
  public ExtractorFactory extractors() {
    return extractors;
  }

  @Override
  public Optional<Embedder> embedder() {
    return Optional.ofNullable(embedder);
  }

  @Override
  public void close() {
    harnesses.close();
  }
}
