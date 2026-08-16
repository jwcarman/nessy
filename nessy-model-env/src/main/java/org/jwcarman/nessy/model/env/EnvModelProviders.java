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
package org.jwcarman.nessy.model.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.model.gemini.GeminiModelProvider;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider follows the key (design §4a, owner: "switching to openai would be simply including
 * that env var"): one method that picks a {@link ModelProvider} by which API key is present in the
 * environment, so an application built against this module switches providers by switching an
 * environment variable rather than its code.
 *
 * <p><strong>Precedence table</strong> (provider-expansion design §3, §7):
 *
 * <table>
 *   <caption>Which key wins</caption>
 *   <tr><th>Env var(s)</th><th>Provider built</th></tr>
 *   <tr><td>{@value #ANTHROPIC_API_KEY_ENV_VAR}</td><td>{@link AnthropicModelProvider}</td></tr>
 *   <tr><td>{@value #OPENAI_API_KEY_ENV_VAR} (plus {@value #OPENAI_BASE_URL_ENV_VAR} if set)</td>
 *       <td>{@link OpenAiModelProvider}</td></tr>
 *   <tr><td>{@value #GEMINI_API_KEY_ENV_VAR}, then {@value #GOOGLE_API_KEY_ENV_VAR}</td>
 *       <td>{@link GeminiModelProvider}</td></tr>
 *   <tr><td>{@value #XAI_API_KEY_ENV_VAR}</td>
 *       <td>{@link OpenAiModelProvider} with {@code baseUrl("https://api.x.ai/v1")}</td></tr>
 * </table>
 *
 * <p>Exactly one key present chooses that provider outright. Two or more present is broken by
 * {@value #NESSY_PROVIDER_ENV_VAR} ({@code anthropic}/{@code openai}/{@code gemini}/{@code xai},
 * alias {@code grok} for {@code xai}), read case-insensitively: naming one of the keys actually
 * present chooses it silently; naming anything else (unset, unrecognized, or a provider whose key
 * is not among those present) falls back to the first present key in the table's row order above —
 * i.e. Anthropic first, then OpenAI, then Gemini, then xAI — logging one WARN naming the default.
 * None present fails fast, naming every variable checked. Each provider is built the same way its
 * own module builds one from an explicit key — {@code Provider.builder().apiKey(key).build()} —
 * mirroring {@link AnthropicModelProvider.Builder#apiKey(String)}, {@link
 * OpenAiModelProvider.Builder#apiKey(String)}, and {@link
 * GeminiModelProvider.Builder#apiKey(String)} exactly, rather than each provider's own {@code
 * fromEnv()}, so the choice this class makes from the map handed to it is the choice that is built
 * — not a second, independent read of the real environment underneath it. {@value
 * #OPENAI_BASE_URL_ENV_VAR} is the one exception: it is layered onto the OpenAI provider via {@link
 * OpenAiModelProvider.Builder#baseUrl(String)} when {@value #OPENAI_API_KEY_ENV_VAR} is the chosen
 * path, exactly as the OpenAI SDK's own {@code fromEnv()} would honor it — the xAI path never reads
 * it, since xAI's base URL is fixed.
 */
public final class EnvModelProviders {

  private static final Logger LOGGER = LoggerFactory.getLogger(EnvModelProviders.class);

  static final String ANTHROPIC_API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";
  static final String OPENAI_API_KEY_ENV_VAR = "OPENAI_API_KEY";
  static final String OPENAI_BASE_URL_ENV_VAR = "OPENAI_BASE_URL";
  static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
  static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";
  static final String XAI_API_KEY_ENV_VAR = "XAI_API_KEY";
  static final String NESSY_PROVIDER_ENV_VAR = "NESSY_PROVIDER";

  /** Honored first by {@link #select(Map)} — see the class javadoc's model-precedence note. */
  static final String NESSY_MODEL_ENV_VAR = "NESSY_MODEL";

  private static final String ANTHROPIC_CHOICE = "anthropic";
  private static final String OPENAI_CHOICE = "openai";
  private static final String GEMINI_CHOICE = "gemini";
  private static final String XAI_CHOICE = "xai";
  private static final String XAI_ALIAS = "grok";
  private static final String XAI_BASE_URL = "https://api.x.ai/v1";

  /** Small/cheap defaults, one per provider — the model each demo used to hardcode itself. */
  private static final String ANTHROPIC_DEFAULT_MODEL = "claude-haiku-4-5-20251001";

  private static final String OPENAI_DEFAULT_MODEL = "gpt-4o-mini";
  private static final String GEMINI_DEFAULT_MODEL = "gemini-2.5-flash";

  /**
   * xAI ships no small/cheap alias; {@code grok-4.6} is verified (docs.x.ai, 2026-08-15) as the
   * vendor's own current general-purpose recommendation ("the most intelligent and fastest model
   * we've built," for code and chat alike) among the models listed there — {@code grok-4.5}, {@code
   * grok-4.3}, the dated {@code grok-4.20-*} variants, and {@code grok-build-0.1}.
   */
  private static final String XAI_DEFAULT_MODEL = "grok-4.6";

  private EnvModelProviders() {}

  /** The public entry point: chooses a provider from the real process environment. */
  public static ModelProvider fromEnv() {
    return select(System.getenv()).provider();
  }

  /** The offline seam: chooses a provider from {@code env} rather than the real environment. */
  static ModelProvider fromEnv(Map<String, String> env) {
    return select(env).provider();
  }

  /**
   * The public entry point for demos and applications that also want to know, and show, what was
   * chosen: chooses a provider from the real process environment, alongside the provider's
   * lowercase name and the model that goes with it.
   *
   * <p><strong>Model precedence:</strong> {@value #NESSY_MODEL_ENV_VAR}, when set and non-blank,
   * wins outright regardless of which provider was chosen — the one way to name a model whose
   * provider instance can't reveal it, such as a Grok, OpenRouter, or LM Studio model reached
   * through {@link OpenAiModelProvider}'s base-url override. Otherwise the chosen provider's own
   * default constant applies: {@value #ANTHROPIC_DEFAULT_MODEL} for Anthropic, {@value
   * #OPENAI_DEFAULT_MODEL} for OpenAI, {@value #GEMINI_DEFAULT_MODEL} for Gemini, {@value
   * #XAI_DEFAULT_MODEL} for xAI.
   */
  public static Selection select() {
    return select(System.getenv());
  }

  /** The offline seam: chooses a {@link Selection} from {@code env} rather than the real one. */
  static Selection select(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    List<Candidate> candidates = presentCandidates(env);
    if (candidates.isEmpty()) {
      throw missingCredentials();
    }
    Candidate chosen =
        candidates.size() == 1
            ? candidates.get(0)
            : tiebreak(env.get(NESSY_PROVIDER_ENV_VAR), candidates);
    var override = env.get(NESSY_MODEL_ENV_VAR);
    var model = override != null && !override.isBlank() ? override : chosen.defaultModel();
    return new Selection(chosen.provider().get(), chosen.name(), model);
  }

  /**
   * Every key present in {@code env}, in the precedence order documented on the class: Anthropic,
   * OpenAI, Gemini, xAI. That order also doubles as the tiebreak default order — the first entry
   * here is the one {@link #tiebreak} falls back to when the preference does not resolve.
   */
  private static List<Candidate> presentCandidates(Map<String, String> env) {
    var candidates = new ArrayList<Candidate>();
    var anthropicKey = env.get(ANTHROPIC_API_KEY_ENV_VAR);
    if (anthropicKey != null) {
      candidates.add(
          new Candidate(ANTHROPIC_CHOICE, ANTHROPIC_DEFAULT_MODEL, () -> anthropic(anthropicKey)));
    }
    var openAiKey = env.get(OPENAI_API_KEY_ENV_VAR);
    if (openAiKey != null) {
      candidates.add(
          new Candidate(OPENAI_CHOICE, OPENAI_DEFAULT_MODEL, () -> openai(openAiKey, env)));
    }
    var geminiKey = geminiKey(env);
    if (geminiKey != null) {
      candidates.add(new Candidate(GEMINI_CHOICE, GEMINI_DEFAULT_MODEL, () -> gemini(geminiKey)));
    }
    var xaiKey = env.get(XAI_API_KEY_ENV_VAR);
    if (xaiKey != null) {
      candidates.add(new Candidate(XAI_CHOICE, XAI_DEFAULT_MODEL, () -> xai(xaiKey)));
    }
    return candidates;
  }

  /**
   * {@value #GEMINI_API_KEY_ENV_VAR} first, then {@value #GOOGLE_API_KEY_ENV_VAR} — Google's own
   * documented pair, in that order — mirroring {@link GeminiModelProvider.Builder#fromEnv()}.
   */
  private static String geminiKey(Map<String, String> env) {
    var key = env.get(GEMINI_API_KEY_ENV_VAR);
    return key != null ? key : env.get(GOOGLE_API_KEY_ENV_VAR);
  }

  private static Candidate tiebreak(String preference, List<Candidate> candidates) {
    var normalized = normalize(preference);
    var explicit = candidates.stream().filter(c -> c.name().equals(normalized)).findFirst();
    if (explicit.isPresent()) {
      return explicit.get();
    }
    var fallback = candidates.get(0);
    LOGGER.warn(
        "multiple model-provider API keys are set ({}); defaulting to {} (set {}={} to choose"
            + " explicitly)",
        candidates.stream().map(Candidate::name).collect(Collectors.joining(", ")),
        fallback.name(),
        NESSY_PROVIDER_ENV_VAR,
        fallback.name());
    return fallback;
  }

  /**
   * Lowercases the preference and resolves the {@value #XAI_ALIAS} alias to {@value #XAI_CHOICE}.
   */
  private static String normalize(String preference) {
    if (preference == null) {
      return null;
    }
    var lower = preference.toLowerCase(Locale.ROOT);
    return XAI_ALIAS.equals(lower) ? XAI_CHOICE : lower;
  }

  private static IllegalStateException missingCredentials() {
    return new IllegalStateException(
        "no model provider credentials found: set "
            + ANTHROPIC_API_KEY_ENV_VAR
            + ", "
            + OPENAI_API_KEY_ENV_VAR
            + ", "
            + GEMINI_API_KEY_ENV_VAR
            + " (or "
            + GOOGLE_API_KEY_ENV_VAR
            + "), or "
            + XAI_API_KEY_ENV_VAR
            + " (and optionally "
            + NESSY_PROVIDER_ENV_VAR
            + " to break a tie if more than one is set)");
  }

  private static ModelProvider anthropic(String apiKey) {
    return AnthropicModelProvider.builder().apiKey(apiKey).build();
  }

  /**
   * {@value #OPENAI_BASE_URL_ENV_VAR} is layered on when present — the provider-expansion design's
   * §7 amendment: local runtimes (LM Studio, Ollama) and gateways (OpenRouter, Gemini's own
   * OpenAI-compat endpoint) become zero-code env citizens the same way xAI is, by pointing this one
   * variable at them alongside any {@value #OPENAI_API_KEY_ENV_VAR} value (local runtimes accept
   * any non-empty string; convention is {@code "lm-studio"}).
   */
  private static ModelProvider openai(String apiKey, Map<String, String> env) {
    var builder = OpenAiModelProvider.builder().apiKey(apiKey);
    var baseUrl = env.get(OPENAI_BASE_URL_ENV_VAR);
    if (baseUrl != null) {
      builder.baseUrl(baseUrl);
    }
    return builder.build();
  }

  private static ModelProvider gemini(String apiKey) {
    return GeminiModelProvider.builder().apiKey(apiKey).build();
  }

  /**
   * Grok as a first-class env citizen with zero new provider code: OpenAI wire protocol, xAI's URL.
   */
  private static ModelProvider xai(String apiKey) {
    return OpenAiModelProvider.builder().apiKey(apiKey).baseUrl(XAI_BASE_URL).build();
  }

  /**
   * One present, keyed provider — {@code name} is one of the lowercase tiebreak vocabulary tokens;
   * {@code defaultModel} is what {@link #select(Map)} uses when {@value #NESSY_MODEL_ENV_VAR} is
   * unset or blank.
   */
  private record Candidate(String name, String defaultModel, Supplier<ModelProvider> provider) {}

  /**
   * What {@link #select()}/{@link #select(Map)} chose: the built {@code provider}, its lowercase
   * name ({@code "anthropic"}/{@code "openai"}/{@code "gemini"}/{@code "xai"} — the same tiebreak
   * vocabulary {@value #NESSY_PROVIDER_ENV_VAR} accepts), and the {@code model} that goes with it,
   * so a caller — a demo's banner, an application's logging — can show what was picked without
   * re-deriving it via {@code instanceof}.
   */
  public record Selection(ModelProvider provider, String providerName, String model) {}
}
