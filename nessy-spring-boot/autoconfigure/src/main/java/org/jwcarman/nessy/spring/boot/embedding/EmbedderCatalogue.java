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
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Turns what an application said -- {@code nessy.embedders.<id>} settings, plus the vendor
 * environment properties that light a hosted preset -- into the embedders that will actually be
 * registered.
 *
 * <p>Pure resolution: no Spring context, no bean, nothing that reads an {@code Environment}
 * directly. The registrar hands it {@code environment::getProperty} as the property lookup so this
 * class stays testable with a plain {@link Map}. The twin of the inference side's catalogue.
 */
final class EmbedderCatalogue {

  private static final EmbedderSettings EMPTY =
      new EmbedderSettings(null, null, null, null, null, null);

  private EmbedderCatalogue() {}

  static List<ResolvedEmbedder> resolve(
      Map<String, EmbedderSettings> settings, Function<String, @Nullable String> property) {
    List<ResolvedEmbedder> lit = new ArrayList<>();
    for (EmbedderPreset preset : EmbedderPreset.CATALOGUE) {
      EmbedderSettings own = settings.getOrDefault(preset.id(), EMPTY);
      // Candidates in order of precedence; the first that is non-null and non-blank wins.
      List<@Nullable String> keys = new ArrayList<>();
      keys.add(own.apiKey());
      preset.keyProperties().forEach(name -> keys.add(property.apply(name)));
      String apiKey = firstNonBlank(keys);
      // enabled=false turns a preset off no matter what ingredient it has; unset means "on if its
      // ingredient is present" for a hosted preset, and stays "off unless said" for a keyless one.
      boolean on;
      if (Boolean.FALSE.equals(own.enabled())) {
        on = false;
      } else if (preset.keyless()) {
        on = Boolean.TRUE.equals(own.enabled());
      } else {
        on = apiKey != null;
      }
      if (!on) {
        continue;
      }
      List<@Nullable String> urls = new ArrayList<>();
      urls.add(own.baseUrl());
      if ("openai".equals(preset.id())) {
        // The same key and URL pair the inference preset of the same name reads, so the two move
        // together.
        urls.add(property.apply("openai.base-url"));
      }
      urls.add(preset.baseUrl());
      Map<String, String> properties = new LinkedHashMap<>(preset.defaultProperties());
      if (own.properties() != null) {
        properties.putAll(own.properties());
      }
      lit.add(
          new ResolvedEmbedder(
              preset.id(),
              own.wire() != null ? own.wire() : preset.wire(),
              firstNonBlank(urls),
              own.vendor() != null ? own.vendor() : preset.vendor(),
              preset.keyless() ? keylessApiKey(own, preset) : apiKey,
              properties));
    }
    settings.forEach(
        (id, own) -> {
          if (Boolean.FALSE.equals(own.enabled())) {
            return;
          }
          if (EmbedderPreset.CATALOGUE.stream().noneMatch(p -> p.id().equals(id))) {
            lit.add(custom(id, own));
          }
        });
    return List.copyOf(lit);
  }

  /** A keyless preset's key: what an application overrode it to, or its placeholder. */
  private static @Nullable String keylessApiKey(EmbedderSettings own, EmbedderPreset preset) {
    List<@Nullable String> keys = new ArrayList<>();
    keys.add(own.apiKey());
    keys.add(preset.keylessApiKey());
    return firstNonBlank(keys);
  }

  private static ResolvedEmbedder custom(String id, EmbedderSettings own) {
    EmbeddingWire wire = own.wire();
    if (wire == null) {
      throw new IllegalStateException(
          "nessy.embedders." + id + ".wire is required: " + id + " is not a preset");
    }
    if (own.baseUrl() == null || own.baseUrl().isBlank()) {
      throw new IllegalStateException(
          "nessy.embedders." + id + ".base-url is required: " + id + " is not a preset");
    }
    if (own.apiKey() == null || own.apiKey().isBlank()) {
      throw new IllegalStateException(
          "nessy.embedders."
              + id
              + ".api-key is required: the "
              + wire.propertyValue()
              + " wire needs a key");
    }
    return new ResolvedEmbedder(
        id,
        wire,
        own.baseUrl(),
        own.vendor() != null ? own.vendor() : wire.defaultVendor(),
        own.apiKey(),
        own.properties() != null ? own.properties() : Map.of());
  }

  /**
   * The first candidate that is non-null and non-blank, or {@code null} when none is: a property
   * set to blank, as {@code ${VAR:}} yields when the variable is unset, is a property nobody set.
   */
  private static @Nullable String firstNonBlank(List<@Nullable String> candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }
}
