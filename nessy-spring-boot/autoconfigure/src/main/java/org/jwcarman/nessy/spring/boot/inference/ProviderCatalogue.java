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
package org.jwcarman.nessy.spring.boot.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Turns what an application said -- {@code nessy.providers.<id>} settings, plus the vendor
 * environment properties that light a hosted preset -- into the providers that will actually be
 * registered.
 *
 * <p>Pure resolution: no Spring context, no bean, nothing that reads an {@code Environment}
 * directly. {@link ProviderRegistrar} hands it {@code environment::getProperty} as the property
 * lookup so this class stays testable with a plain {@link Map}.
 */
final class ProviderCatalogue {

  private static final ProviderSettings EMPTY = new ProviderSettings(null, null, null, null, null);

  private ProviderCatalogue() {}

  static List<ResolvedProvider> resolve(
      Map<String, ProviderSettings> settings, Function<String, @Nullable String> property) {
    List<ResolvedProvider> lit = new ArrayList<>();
    for (Preset preset : Preset.CATALOGUE) {
      ProviderSettings own = settings.getOrDefault(preset.id(), EMPTY);
      // Candidates in order of precedence; the first that is non-null and non-blank wins.
      List<String> keys = new ArrayList<>();
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
      List<String> urls = new ArrayList<>();
      urls.add(own.baseUrl());
      if ("openai".equals(preset.id())) {
        urls.add(property.apply("openai.base-url"));
      }
      urls.add(preset.baseUrl());
      String baseUrl = firstNonBlank(urls);
      lit.add(
          new ResolvedProvider(
              preset.id(),
              own.wire() != null ? own.wire() : preset.wire(),
              baseUrl,
              own.vendor() != null ? own.vendor() : preset.vendor(),
              preset.keyless() ? keylessApiKey(own, preset) : apiKey));
    }
    settings.forEach(
        (id, own) -> {
          if (Boolean.FALSE.equals(own.enabled())) {
            return;
          }
          if (Preset.CATALOGUE.stream().noneMatch(p -> p.id().equals(id))) {
            lit.add(custom(id, own));
          }
        });
    return List.copyOf(lit);
  }

  /** A keyless preset's key: what an application overrode it to, or its placeholder. */
  private static String keylessApiKey(ProviderSettings own, Preset preset) {
    List<String> keys = new ArrayList<>();
    keys.add(own.apiKey());
    keys.add(preset.keylessApiKey());
    return firstNonBlank(keys);
  }

  private static ResolvedProvider custom(String id, ProviderSettings own) {
    if (own.wire() == null) {
      throw new IllegalStateException(
          "nessy.providers." + id + ".wire is required: " + id + " is not a preset");
    }
    if (own.baseUrl() == null || own.baseUrl().isBlank()) {
      throw new IllegalStateException(
          "nessy.providers." + id + ".base-url is required: " + id + " is not a preset");
    }
    String vendor = own.vendor() != null ? own.vendor() : own.wire().defaultVendor();
    return new ResolvedProvider(id, own.wire(), own.baseUrl(), vendor, own.apiKey());
  }

  /** The first candidate that is non-null and non-blank, or {@code null} when none is. */
  private static @Nullable String firstNonBlank(List<@Nullable String> candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }
}
