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
package org.jwcarman.nessy.inference.openai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code openai.} vendor properties (spec §9a, §9b): the names both OpenAI wires parse, each
 * wire's clash table, and everything else passed through into the request body. One reading for
 * both wires, so an agent type switching between them keeps its settings.
 */
final class OpenAiProperties {

  static final String PREFIX = "openai.";
  static final String EFFORT = "reasoning.effort";
  static final String SUMMARY = "reasoning.summary";
  static final String STRICT = "tools.strict";
  static final String SERVICE_TIER = "service_tier";

  static final Set<String> KNOWN = Set.of(EFFORT, SUMMARY, STRICT, SERVICE_TIER);

  static final String CONVERSATION = "the conversation the engine assembles";
  static final String TOOLS = "the tools the harness binds";
  static final String TOOL_CHOICE = "the tool choice the engine makes";
  static final String SHAPE = "the answer's shape the harness asks for";
  static final String STREAMING = "the adapter, which always streams";

  /** What the chat wire's typed settings, or the adapter itself, already decide (§9a). */
  private static final Map<String, String> CHAT_CLASHES =
      Map.of(
          "model", "InferenceConfig.model",
          "messages", CONVERSATION,
          "max_completion_tokens", "InferenceConfig.maxTokens",
          "max_tokens", "InferenceConfig.maxTokens",
          "tools", TOOLS,
          "tool_choice", TOOL_CHOICE,
          "response_format", SHAPE,
          "stream", STREAMING,
          "stream_options", "the adapter, which always asks for usage on the stream");

  /** Why the Responses adapter fixes a field, quoted in the refusal (Responses record §5a). */
  private static final String STATELESS =
      "the Responses adapter, which is stateless because the event log is the only conversation";

  /** What the Responses wire's typed settings, or the adapter itself, already decide (§9b). */
  private static final Map<String, String> RESPONSES_CLASHES =
      Map.ofEntries(
          Map.entry("model", "InferenceConfig.model"),
          Map.entry("input", CONVERSATION),
          Map.entry("instructions", "the system prompt the harness sends"),
          Map.entry("max_output_tokens", "InferenceConfig.maxTokens"),
          Map.entry("tools", TOOLS),
          Map.entry("tool_choice", TOOL_CHOICE),
          Map.entry("text", SHAPE),
          Map.entry("stream", STREAMING),
          Map.entry("store", STATELESS),
          Map.entry("include", STATELESS),
          Map.entry("previous_response_id", STATELESS),
          Map.entry("conversation", STATELESS),
          Map.entry("background", STATELESS));

  /** The object the adapter builds when either reasoning name is set (plan ruling 6). */
  private static final String REASONING = "reasoning";

  private static final Logger log = LoggerFactory.getLogger(OpenAiProperties.class);

  private OpenAiProperties() {}

  /**
   * The {@code openai.} properties once read.
   *
   * @param strict whether function tools go out strict; always false on the chat wire unless asked
   *     for
   * @param passThrough every other name under the prefix, nested by path, values as JSON literals
   */
  record Read(
      Optional<String> effort,
      Optional<String> summary,
      boolean strict,
      Optional<String> serviceTier,
      Map<String, Object> passThrough) {}

  /** The chat wire's reading of the merged provider and agent-type map (§9a). */
  static Read chat(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Map<String, String> clashes = new LinkedHashMap<>(CHAT_CLASHES);
    if (own.containsKey(EFFORT)) {
      clashes.put("reasoning_effort", "property '" + PREFIX + EFFORT + "'");
    }
    VendorProperties.refuseClashes(PREFIX, passedThrough(own), clashes);
    if (own.containsKey(SUMMARY)) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + SUMMARY
              + "' cannot be sent on the openai-chat wire, which has no reasoning summary;"
              + " the openai-responses wire carries it");
    }
    return read(own, mapper);
  }

  /** The Responses wire's reading of the merged provider and agent-type map (§9b). */
  static Read responses(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Map<String, String> clashes = new LinkedHashMap<>(RESPONSES_CLASHES);
    String known = null;
    if (own.containsKey(EFFORT)) {
      known = EFFORT;
    } else if (own.containsKey(SUMMARY)) {
      known = SUMMARY;
    }
    if (known != null) {
      for (String name : own.keySet()) {
        boolean underReasoning = name.equals(REASONING) || name.startsWith(REASONING + ".");
        if (underReasoning && !KNOWN.contains(name)) {
          clashes.put(name, "property '" + PREFIX + known + "'");
        }
      }
    }
    VendorProperties.refuseClashes(PREFIX, passedThrough(own), clashes);
    Read read = read(own, mapper);
    if (own.containsKey(STRICT) && !read.strict()) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + STRICT
              + "' is false, and the openai-responses wire sends function tools strict"
              + " regardless; remove the property, or use the openai-chat wire");
    }
    return read;
  }

  /** The known names parsed, the rest nested, over a map already filtered to this prefix. */
  static Read read(Map<String, String> own, JsonMapper mapper) {
    Optional<String> effort = string(own, EFFORT);
    Optional<String> summary = string(own, SUMMARY);
    Optional<String> serviceTier = string(own, SERVICE_TIER);
    boolean strict =
        own.containsKey(STRICT)
            && VendorProperties.requireBoolean(PREFIX + STRICT, own.get(STRICT));
    return new Read(
        effort,
        summary,
        strict,
        serviceTier,
        VendorProperties.nest(PREFIX, passedThrough(own), mapper));
  }

  /** The names this adapter does not parse itself: the ones a clash can be about. */
  private static Map<String, String> passedThrough(Map<String, String> own) {
    Map<String, String> rest = new LinkedHashMap<>(own);
    rest.keySet().removeAll(KNOWN);
    return rest;
  }

  /** A provider is one adapter: a provider-level entry under another prefix is a mistake (§6a). */
  static void requireOwn(Map<String, String> properties) {
    for (String name : properties.keySet()) {
      if (!name.startsWith(PREFIX)) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' is not under '"
                + PREFIX
                + "'; a provider reads only its own prefix");
      }
    }
  }

  /**
   * Another adapter's settings are the design working, so they are named at DEBUG and no louder.
   */
  static void logIgnored(Map<String, String> merged) {
    if (log.isDebugEnabled()) {
      List<String> others = merged.keySet().stream().filter(n -> !n.startsWith(PREFIX)).toList();
      if (!others.isEmpty()) {
        log.debug("NESSY INFERENCE: properties for other adapters, ignored here: {}", others);
      }
    }
  }

  private static Optional<String> string(Map<String, String> own, String name) {
    return Optional.ofNullable(own.get(name))
        .map(value -> VendorProperties.requireString(PREFIX + name, value));
  }
}
