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
package org.jwcarman.nessy.spring.boot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.jwcarman.nessy.spi.model.Capability;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

/**
 * Everything about a harness that is CONFIGURATION rather than code (watchman spec §1.1). The list
 * is deliberately short and deliberately closed: tools, grants and approvers are beans, because
 * they are code, and a property file is the wrong place to express authority.
 *
 * <p>The model id is not here. {@code ModelDiscovery} reads {@code NESSY_MODEL} from the process
 * environment for that, and an application that wants to choose the model any other way declares
 * its own {@link org.jwcarman.nessy.spi.model.Model} bean, which wins outright.
 *
 * @param type the recipe's name — the first coordinate of every durable address
 * @param systemPrompt the harness's system prompt; exactly one of this and {@code systemPromptFile}
 *     must be set
 * @param systemPromptFile a resource ({@code classpath:}, {@code file:}) whose whole contents are
 *     the system prompt — for prompts long enough that a properties file is the wrong home
 * @param staleness how long a quiet phase may sit before the recovery arm re-fires it
 * @param backlogCapacity the per-scope backlog depth
 * @param capabilities what the harness CANNOT RUN WITHOUT — {@code
 *     nessy.capabilities=prompt-caching,thinking}; empty by default. Naming one here makes a model
 *     that lacks it fail at startup rather than misbehave mid-turn. Formerly a request nothing
 *     checked: a provider that cannot do one says so, and nothing fails. {@code PROMPT_CACHING} is
 *     the one a long-running agent wants, since a system prompt and a tool schema resent every few
 *     minutes are exactly what a provider cache is for; the {@code
 *     gen_ai.usage.cache_read.input_tokens} and {@code cache_write} attributes on the chat span are
 *     how you tell whether it worked. Add {@code prompt-caching-1h} when rounds are further apart
 *     than the provider's default entry lives — Anthropic's is five minutes, so a half-hourly agent
 *     can never read one back — at the cost of a higher write rate (2x base input on Anthropic,
 *     against 1.25x for the default).
 */
@ConfigurationProperties("nessy")
public record NessyProperties(
    String type,
    String systemPrompt,
    Resource systemPromptFile,
    Duration staleness,
    Integer backlogCapacity,
    Set<Capability> capabilities) {

  /**
   * Defaults applied here rather than in the auto-configuration, so that reading this record tells
   * you what an unconfigured harness does. Each matches {@code HarnessConfig}'s own default exactly
   * — the starter changes no behaviour, it only reaches the same knobs from a properties file.
   */
  public NessyProperties {
    type = type == null || type.isBlank() ? "agent" : type;
    staleness = staleness == null ? Duration.ofMinutes(5) : staleness;
    backlogCapacity = backlogCapacity == null ? 1024 : backlogCapacity;
    // An EnumSet copy rather than Set.copyOf: it is the natural set for an enum, and it makes the
    // component immutable, which a record component reached from many threads had better be.
    capabilities =
        capabilities == null || capabilities.isEmpty()
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(capabilities));
  }

  /**
   * The system prompt, from whichever of the two properties was set.
   *
   * @throws IllegalStateException if neither or both were set — a harness with no system prompt is
   *     not a harness, and two sources for one prompt is a configuration mistake worth failing the
   *     context over rather than silently preferring one
   */
  public String resolveSystemPrompt() {
    boolean inline = systemPrompt != null && !systemPrompt.isBlank();
    if (inline && systemPromptFile != null) {
      throw new IllegalStateException(
          "set either nessy.system-prompt or nessy.system-prompt-file, not both");
    }
    if (inline) {
      return systemPrompt;
    }
    if (systemPromptFile == null) {
      throw new IllegalStateException(
          "nessy.system-prompt (or nessy.system-prompt-file) is required: the harness's system"
              + " prompt has no default");
    }
    return read(systemPromptFile);
  }

  private static String read(Resource resource) {
    try (var in = resource.getInputStream()) {
      return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "nessy.system-prompt-file could not be read: " + resource.getDescription(), e);
    }
  }
}
