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
package org.jwcarman.nessy.engine.chapter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.OpenTurns;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.store.TurnHistories;

/**
 * A chapter policy that lets the model say where chapters begin.
 *
 * <p>The model is the one party that knows when a conversation has changed subject, so it is given
 * a tool, {@code begin_chapter}, to call at the start of the turn that opens a new chapter. This
 * policy reads the open turns and closes the chapter at the turn immediately before every open turn
 * that made that call. The first open turn is never a boundary: there is nothing before it to
 * close, so a call there ends nothing.
 *
 * <p>The tool itself only acknowledges. The call is the signal, and it is found in the turn's own
 * record, so the decision is replayable from the history alone and nothing else is stored.
 *
 * <p>{@link #feature(TurnHistories)} equips an agent with both halves in one call.
 */
public final class DeclaredChapters implements ChapterPolicy {

  /** The name of the tool the model calls to begin a chapter. */
  public static final ToolName TOOL_NAME = new ToolName("begin_chapter");

  private static final String ACKNOWLEDGEMENT = "A new chapter begins with this turn.";

  /** What the model says when it begins a chapter. */
  public record Beginning(
      @JsonPropertyDescription("A few words naming what the new chapter is about") String title) {}

  private final TurnHistories histories;

  /**
   * @param histories where the open turns are read from, to find the ones that called the tool
   */
  public DeclaredChapters(TurnHistories histories) {
    this.histories = Objects.requireNonNull(histories, "histories must not be null");
  }

  /**
   * The tool the model calls to say a new chapter begins with this turn.
   *
   * <p>It does nothing but acknowledge; the call, seen in the turn's record, is what ends the
   * chapter before.
   */
  public static Tool<Beginning> tool() {
    return new BeginChapter();
  }

  /** Installs the tool and the policy on an agent: one call equips it. */
  public static Customizer<HarnessConfig<?>> feature(TurnHistories histories) {
    Objects.requireNonNull(histories, "histories must not be null");
    return config -> config.tool(tool()).chapterPolicy(new DeclaredChapters(histories));
  }

  @Override
  public List<TurnId> ends(OpenTurns open) {
    if (open.turns().isEmpty()) {
      return List.of();
    }
    List<Turn> turns =
        histories
            .forAgent(open.agentType(), open.agentId())
            .turnsBetween(open.turns().getFirst(), open.turns().getLast());
    List<TurnId> ends = new ArrayList<>();
    for (int i = 1; i < turns.size(); i++) {
      if (begins(turns.get(i))) {
        ends.add(turns.get(i - 1).id());
      }
    }
    return ends;
  }

  private static boolean begins(Turn turn) {
    return turn.exchanges().stream()
        .map(Exchange::calls)
        .flatMap(List::stream)
        .anyMatch(call -> call.name().equals(TOOL_NAME));
  }

  private record BeginChapter() implements Tool<Beginning> {

    @Override
    public Class<Beginning> inputType() {
      return Beginning.class;
    }

    @Override
    public ToolName name() {
      return TOOL_NAME;
    }

    @Override
    public String description() {
      return "Say that a new chapter of this conversation begins. Call it when the conversation"
          + " moves to a new subject, or when a piece of business is finished and another begins."
          + " Call it at the start of the turn that opens the new chapter, before anything else."
          + " The title is a few words naming what the new chapter is about.";
    }

    @Override
    public Awaited<ToolResult> call(ToolCallRequest<Beginning> request) {
      return Awaited.ready(ToolResult.ok(new Block.Text(ACKNOWLEDGEMENT)));
    }
  }
}
