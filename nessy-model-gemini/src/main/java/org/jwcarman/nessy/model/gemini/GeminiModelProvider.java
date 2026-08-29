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
package org.jwcarman.nessy.model.gemini;

import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelDescription;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The vendor gateway for Gemini: owns a {@link GeminiClient} and hands out {@link Model} handles
 * bound to one model id apiece via {@link #model(String)}, talking to the Gemini Developer API via
 * Google's java-genai SDK with a plain API key (Vertex AI's project/location/credentials auth is
 * out of scope for v1).
 *
 * <p>Everything upstream of the handle it returns is pure translation ({@link GeminiRequests},
 * {@link GeminiStream}); this class is the one place that owns the client and actually talks to the
 * network.
 *
 * <p>{@link Capability#PARALLEL_TOOL_CALLS} <em>is</em> advertised: {@link GeminiRequests} and
 * {@link GeminiStream} already handle several {@code functionCall} parts arriving on one turn —
 * {@code GeminiStreamTest}'s {@code multiple_function_calls_in_one_turn_each_emit_in_order} proves
 * it — so claiming it is honest, not aspirational. {@link Capability#THINKING} is deliberately
 * absent: Gemini's {@code thought}-flagged parts are dropped by {@link GeminiStream} rather than
 * translated, and wiring them up requires both a thought-part-to-{@code ThinkingChunk} mapping and
 * a capabilities flag — banked, not done, per the provider-expansion design (§2, §6). {@link
 * Capability#PROMPT_CACHING} and {@link Capability#IMAGE_INPUT} are equally unadvertised: neither
 * is wired into this module's request/response mapping, so neither is claimed.
 */
public final class GeminiModelProvider implements ModelProvider {

  /**
   * The OpenTelemetry GenAI semantic conventions' pinned value for this vendor, reported by every
   * {@link Model} this gateway mints as its {@link Model#provider()} (agentic-o11y spec §1.1).
   */
  static final String PROVIDER = "gcp.gemini";

  private static final Set<Capability> CAPABILITIES = Set.of(Capability.PARALLEL_TOOL_CALLS);

  private final GeminiClient client;

  GeminiModelProvider(GeminiClient client) {
    this.client = client;
  }

  /**
   * The blessed one-call shape: equivalent to {@code create(GeminiProviderConfig::fromEnv)}.
   * Delegates credential resolution to {@link GeminiProviderConfig#fromEnv()}.
   */
  public static GeminiModelProvider fromEnv() {
    return create(GeminiProviderConfig::fromEnv);
  }

  /**
   * Builds a {@link GeminiModelProvider} from a live {@link GeminiProviderConfig}: {@code
   * customizer} fills it in, then this factory validates its required field and constructs the
   * finished provider. No public {@code build()} survives here; the factory is the only place a
   * {@link GeminiProviderConfig} ever turns into a {@link GeminiModelProvider} (design of record
   * 2026-08-16 §1).
   */
  public static GeminiModelProvider create(GeminiProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    GeminiProviderConfig config = new GeminiProviderConfig();
    customizer.customize(config);
    return config.build();
  }

  @Override
  public Model model(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    return new GeminiModel(id);
  }

  @Override
  public String name() {
    return "Gemini";
  }

  /**
   * Closes the SDK {@code Client} this gateway BUILT, through the {@link GeminiClient} seam. A
   * client handed in through {@link GeminiProviderConfig#client(com.google.genai.Client)} is never
   * closed here: it was never opened here — the same ownership rule {@code BedrockModelProvider}
   * keeps.
   */
  @Override
  public void close() {
    client.close();
  }

  /**
   * A flyweight bound handle: pins one model id over the shared {@link #client}. Capability tables
   * are per-vendor today ({@link #CAPABILITIES}); a future change could make this per-model without
   * disturbing the gateway.
   */
  private final class GeminiModel implements Model {

    private final String id;

    private GeminiModel(String id) {
      this.id = id;
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      var contents = GeminiRequests.toContents(request);
      var config = GeminiRequests.toConfig(request);
      return client.generateContentStream(id, contents, config);
    }

    /**
     * What this model is. The context window is a per-provider constant for now — Gemini 1.5+ reads
     * a million.
     *
     * <p>A per-model figure belongs here the day one is available; a wrong window is caught at
     *
     * <p>resolution rather than mid-turn, which is the point of reporting it at all.
     */
    @Override
    public ModelDescription describe() {

      return new ModelDescription(id, PROVIDER, 1_000_000, CAPABILITIES);
    }
  }
}
