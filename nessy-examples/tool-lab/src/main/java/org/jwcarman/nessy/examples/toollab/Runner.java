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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.chapter.Transcripts;
import org.jwcarman.nessy.examples.toollab.Task.Called;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;
import tools.jackson.databind.JsonNode;

/**
 * Runs one task under one variant: one turn whose input is the task's prompt, and up to {@value
 * #MAX_ROUNDS} calls to the model. A reply that asks for tools has them run, and the exchange is
 * added to the turn; a reply that is text ends the run.
 */
final class Runner {

  /** The most calls to the model in one run. */
  static final int MAX_ROUNDS = 12;

  private static final int ACTION_LINE = 200;

  private final InferenceProvider provider;
  private final InferenceOptions options;
  private final Catalog catalog;
  private final Bm25 index;
  private final Duration pause;

  Runner(InferenceProvider provider, InferenceOptions options, Catalog catalog, Duration pause) {
    this.provider = provider;
    this.options = options;
    this.catalog = catalog;
    this.index = new Bm25(catalog.tools());
    this.pause = pause;
  }

  /** Runs {@code task} under {@code variant}; {@code runIndex} only labels the record. */
  RunRecord run(Task task, Variant variant, int runIndex) {
    long started = System.nanoTime();
    SystemPrompt system = variant.systemPrompt(catalog);
    Turn turn =
        new Turn(
            new TurnId(1),
            new Input(new Seq(1), List.of(new Block.Text(task.prompt()))),
            List.of(),
            null);
    Progress progress = new Progress();
    Ending ending = Ending.EXHAUSTED;
    String answer = "";
    String note = "";
    while (progress.rounds < MAX_ROUNDS) {
      List<Turn> turns = List.of(turn);
      Toolset toolset = variant.toolset(catalog, turns);
      progress.maxOffered = Math.max(progress.maxOffered, toolset.offers().size());
      InferenceRequest request =
          new InferenceRequest(system, InferenceContext.of(turns), toolset, options);
      InferenceResult result;
      progress.rounds++;
      try {
        result = Models.retrying(() -> provider.infer(request), pause);
      } catch (IllegalStateException gaveUp) {
        ending = Ending.FAULT;
        note = gaveUp.getMessage();
        break;
      }
      progress.usage = progress.usage.plus(result.usage());
      switch (result) {
        case InferenceResult.Actions actions -> {
          turn = withExchange(turn, actions, toolset, progress);
          continue;
        }
        case InferenceResult.Answer a -> {
          ending = Ending.ANSWER;
          answer = Transcripts.text(a.blocks()).strip();
        }
        case InferenceResult.Truncated t -> {
          ending = Ending.TRUNCATED;
          answer = Transcripts.text(t.blocks()).strip();
        }
        case InferenceResult.Refusal r -> {
          ending = Ending.REFUSAL;
          note = r.category();
        }
        case InferenceResult.Fault f -> {
          ending = Ending.FAULT;
          note = f.failure().reason();
        }
      }
      break;
    }
    Grade grade = Grader.grade(task, progress.catalogCalled, ending, catalog);
    return new RunRecord(
        task.id(),
        task.kind(),
        variant,
        runIndex,
        ending,
        grade.success(),
        progress.catalogCalled.size(),
        grade.wrongCalls(),
        progress.meta,
        progress.unloaded,
        progress.unknown,
        progress.rounds,
        progress.usage,
        Duration.ofNanos(System.nanoTime() - started).toMillis(),
        progress.maxOffered,
        answer,
        note,
        List.copyOf(progress.log));
  }

  private Turn withExchange(
      Turn turn, InferenceResult.Actions actions, Toolset toolset, Progress progress) {
    Set<String> offered = new HashSet<>();
    toolset.offers().forEach(offer -> offered.add(offer.name().value()));
    List<ToolOutcome> outcomes = new ArrayList<>();
    Map<CallId, String> lines = new LinkedHashMap<>();
    for (Block.ActionRequestContent block : actions.blocks()) {
      if (block instanceof Block.ToolCall call) {
        outcomes.add(execute(call, offered, progress));
        String line = call.name().value() + "(" + call.arguments() + ")";
        String cut = line.length() <= ACTION_LINE ? line : line.substring(0, ACTION_LINE);
        lines.put(call.id(), cut);
        progress.log.add(cut);
      }
    }
    List<Exchange> exchanges = new ArrayList<>(turn.exchanges());
    Seq seq = new Seq(turn.input().seq().value() + exchanges.size() + 1);
    exchanges.add(new Exchange(seq, actions.blocks(), outcomes, lines, Map.of()));
    return new Turn(turn.id(), turn.input(), exchanges, null);
  }

  private ToolOutcome execute(Block.ToolCall call, Set<String> offered, Progress progress) {
    String name = call.name().value();
    CallId id = call.id();
    ParsedArguments parsed = ParsedArguments.of(call.arguments());
    boolean inCatalog = catalog.find(name).isPresent();
    if (offered.contains(name) && MetaTools.isMeta(name)) {
      progress.meta++;
      return meta(call, parsed);
    }
    if (inCatalog) {
      boolean runs = offered.contains(name) && parsed.problem() == null;
      progress.catalogCalled.add(new Called(name, parsed.node(), runs));
      if (!offered.contains(name)) {
        progress.unloaded++;
        return new ToolOutcome.Failed(id, name + " is not loaded; load it with load_tools first");
      }
      if (parsed.problem() != null) {
        return new ToolOutcome.Failed(id, parsed.problem());
      }
      String result = catalog.find(name).orElseThrow().run(parsed.node());
      return new ToolOutcome.Succeeded(id, List.of(new Block.Text(result)));
    }
    progress.unknown++;
    return new ToolOutcome.Failed(id, "no such tool");
  }

  private ToolOutcome meta(Block.ToolCall call, ParsedArguments parsed) {
    if (parsed.problem() != null) {
      return new ToolOutcome.Failed(call.id(), parsed.problem());
    }
    try {
      String result =
          call.name().value().equals(MetaTools.SEARCH)
              ? MetaTools.search(parsed.node(), index)
              : MetaTools.load(parsed.node(), catalog);
      return new ToolOutcome.Succeeded(call.id(), List.of(new Block.Text(result)));
    } catch (IllegalArgumentException refused) {
      return new ToolOutcome.Failed(call.id(), refused.getMessage());
    }
  }

  /** What the model sent as arguments: the object, or what is wrong with it. */
  private record ParsedArguments(JsonNode node, String problem) {

    static ParsedArguments of(String arguments) {
      if (arguments.isBlank()) {
        return new ParsedArguments(Schemas.MAPPER.createObjectNode(), null);
      }
      try {
        JsonNode parsed = Schemas.MAPPER.readTree(arguments);
        if (!parsed.isObject()) {
          return new ParsedArguments(
              Schemas.MAPPER.createObjectNode(), "arguments must be a JSON object");
        }
        return new ParsedArguments(parsed, null);
      } catch (RuntimeException invalid) {
        return new ParsedArguments(
            Schemas.MAPPER.createObjectNode(), "arguments are not valid JSON");
      }
    }
  }

  /** The counts a run accumulates. */
  private static final class Progress {
    int rounds;
    int maxOffered;
    int meta;
    int unloaded;
    int unknown;
    Tally usage = Tally.EMPTY;
    final List<Called> catalogCalled = new ArrayList<>();
    final List<String> log = new ArrayList<>();
  }
}
