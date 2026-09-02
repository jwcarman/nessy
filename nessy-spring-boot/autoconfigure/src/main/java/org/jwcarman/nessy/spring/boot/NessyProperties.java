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
import java.util.EnumSet;
import java.util.Set;
import org.jwcarman.nessy.spi.model.Capability;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.util.FileCopyUtils;

/**
 * Everything the starter reads from {@code application.yaml}, under {@code nessy}.
 *
 * <p>Defaults live in the compact constructor rather than in the auto-configuration, so that
 * reading this record tells you what an unconfigured harness does.
 *
 * @param type what kind of agent this application runs — namespaces every agent id and everything
 *     they persist
 * @param systemPrompt the standing instruction, inline
 * @param systemPromptFile the standing instruction, from a classpath or file resource; mutually
 *     exclusive with {@link #systemPrompt}
 * @param model which model the agents talk to, resolved against the application's {@code
 *     ModelProvider}
 * @param provider the semconv {@code gen_ai.provider.name} for the vendor behind the ModelProvider
 *     — {@code openai}, {@code anthropic}, {@code gcp.gemini}, {@code aws.bedrock}. Configured
 *     rather than discovered, because a Model no longer reports its own vendor and only the
 *     application that built the provider knows which one it is. Each adapter publishes the right
 *     value as its own {@code PROVIDER_NAME} constant.
 * @param maxTokens the longest answer to allow
 * @param capabilities what the application would LIKE its provider to use; an adapter that cannot
 *     oblige simply does not
 * @param replyTokenEncryptionKeys the AES keys a {@code ReplyToken}'s coordinates are sealed with,
 *     newest first — base64, and 16, 24 or 32 bytes each (use 32). Named for what they ARE: "reply
 *     keys" read as an address book rather than as secrets, and nobody could tell from the property
 *     what to put in it. Absent means an EPHEMERAL key, which is fine for a single process that
 *     never restarts mid-approval and wrong for anything else: a token minted before a restart
 *     cannot be read after one, so every parked call becomes unanswerable.
 */
@ConfigurationProperties("nessy")
public record NessyProperties(
    String type,
    String systemPrompt,
    Resource systemPromptFile,
    String model,
    String provider,
    Integer maxTokens,
    Set<Capability> capabilities,
    java.util.List<String> replyTokenEncryptionKeys) {

  public NessyProperties {
    type = type == null || type.isBlank() ? "agent" : type;
    provider = provider == null || provider.isBlank() ? "unknown" : provider;
    maxTokens = maxTokens == null ? 4096 : maxTokens;
    capabilities =
        capabilities == null || capabilities.isEmpty()
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(capabilities));
    replyTokenEncryptionKeys =
        replyTokenEncryptionKeys == null
            ? java.util.List.of()
            : java.util.List.copyOf(replyTokenEncryptionKeys);
  }

  /**
   * The system prompt, from whichever source was configured.
   *
   * @throws IllegalStateException if both were given — silently preferring one would make a
   *     misconfigured prompt very hard to notice
   */
  public String resolveSystemPrompt() {
    boolean inline = systemPrompt != null && !systemPrompt.isBlank();
    if (inline && systemPromptFile != null) {
      throw new IllegalStateException(
          "set nessy.system-prompt or nessy.system-prompt-file, not both");
    }
    if (inline) {
      return systemPrompt;
    }
    return systemPromptFile == null ? "" : read(systemPromptFile);
  }

  private static String read(Resource resource) {
    try (var reader =
        new java.io.InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
      return FileCopyUtils.copyToString(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + resource, e);
    }
  }
}
