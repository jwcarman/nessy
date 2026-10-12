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
package org.jwcarman.nessy.examples.toollab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a run of the lab was asked to do.
 *
 * @param provider which provider to call
 * @param model the model to call
 * @param variants the variants to run, in order
 * @param size how many tools the catalog holds
 * @param tasks how many tasks to run, from the front of the file
 * @param runs how many times to run each task under each variant
 * @param out the directory the result file goes in
 * @param baseUrl the address of a local server; {@code lmstudio} only
 * @param maxTokens the output limit for every call
 * @param thinking the Anthropic thinking type; {@code anthropic} only
 * @param probeUnoffered whether to run the probe instead of the tasks
 */
record Settings(
    String provider,
    String model,
    List<Variant> variants,
    int size,
    int tasks,
    int runs,
    Path out,
    String baseUrl,
    int maxTokens,
    String thinking,
    boolean probeUnoffered) {

  private static final List<String> KNOWN =
      List.of(
          "provider",
          "model",
          "variants",
          "size",
          "tasks",
          "runs",
          "out",
          "base-url",
          "max-tokens",
          "thinking",
          "probe-unoffered");

  /**
   * Reads the command line.
   *
   * @throws IllegalArgumentException saying what is wrong and, for a choice, the choices
   */
  static Settings parse(String[] args) {
    Map<String, String> given = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String name = args[i];
      if (!name.startsWith("--")) {
        throw new IllegalArgumentException("expected an option, found: " + name);
      }
      if (name.equals("--probe-unoffered")) {
        given.put("probe-unoffered", "true");
        continue;
      }
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException(name + " needs a value");
      }
      given.put(name.substring(2), args[++i]);
    }
    for (String name : given.keySet()) {
      if (!KNOWN.contains(name)) {
        throw new IllegalArgumentException("unknown option --" + name + "; options are " + KNOWN);
      }
    }
    String provider = required(given, "provider");
    if (!ToolLab.PROVIDERS.contains(provider)) {
      throw new IllegalArgumentException(
          "unknown provider '%s'; choose one of %s".formatted(provider, ToolLab.PROVIDERS));
    }
    String model = required(given, "model");
    String baseUrl = given.get("base-url");
    if (baseUrl != null && !provider.equals("lmstudio")) {
      throw new IllegalArgumentException("--base-url applies only to --provider lmstudio");
    }
    String thinking = given.get("thinking");
    if (thinking != null && !provider.equals("anthropic")) {
      throw new IllegalArgumentException("--thinking applies only to --provider anthropic");
    }
    int size = positive(given, "size", 100);
    if (size < HandWritten.tools().size()) {
      throw new IllegalArgumentException(
          "--size must be at least %d, the number of hand-written tools"
              .formatted(HandWritten.tools().size()));
    }
    int tasks = positive(given, "tasks", 10);
    int available = Tasks.load().size();
    if (tasks > available) {
      throw new IllegalArgumentException(
          "--tasks %d is more than the %d tasks there are".formatted(tasks, available));
    }
    return new Settings(
        provider,
        model,
        variants(given.getOrDefault("variants", "v0,v1,v2")),
        size,
        tasks,
        positive(given, "runs", 1),
        Path.of(given.getOrDefault("out", ".")),
        baseUrl,
        positive(given, "max-tokens", 2048),
        thinking,
        given.containsKey("probe-unoffered"));
  }

  private static List<Variant> variants(String list) {
    List<Variant> variants = new ArrayList<>();
    for (String label : list.split(",")) {
      variants.add(Variant.parse(label));
    }
    return List.copyOf(variants);
  }

  private static String required(Map<String, String> given, String name) {
    String value = given.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("--" + name + " is required");
    }
    return value;
  }

  private static int positive(Map<String, String> given, String name, int otherwise) {
    String text = given.get(name);
    if (text == null) {
      return otherwise;
    }
    try {
      int value = Integer.parseInt(text.strip());
      if (value > 0) {
        return value;
      }
    } catch (NumberFormatException notANumber) {
      // falls through to the same message as a number that is too small
    }
    throw new IllegalArgumentException(
        "--%s must be a positive whole number: %s".formatted(name, text));
  }
}
