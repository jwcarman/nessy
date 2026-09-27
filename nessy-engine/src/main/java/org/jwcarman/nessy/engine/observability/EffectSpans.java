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
package org.jwcarman.nessy.engine.observability;

import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.AgentEffect;

/**
 * What an effect's span is called, wherever the effect is performed.
 *
 * <p><b>One place, because two doors perform the same effects.</b> The queued door dispatches them
 * from a table and the direct door runs them on its own threads, and a reader comparing the two
 * should be reading the same words. This lived inside the dispatcher while only the dispatcher
 * named anything; copying it to the other door would have been a second switch over the same
 * grammar, and the two would have drifted the first time an effect was added.
 *
 * <p><b>Not a {@code gen_ai} operation.</b> An effect is this engine's own unit of work -- a thing
 * with a deadline, an attempt count and a row or a thread -- so it is named for what it is rather
 * than borrowed from the GenAI conventions. The model call inside it is the {@code chat} span, and
 * that one IS semconv's.
 */
public final class EffectSpans {

  /** The observation name every effect span shares, whichever door performed it. */
  public static final String EFFECT = "nessy.effect";

  private EffectSpans() {}

  /**
   * Its kind, and for a call the tool, the way {@code execute_tool <name>} reads.
   *
   * <p>An approval often has no span beneath it to say which call it was for, which is why the tool
   * is named here rather than left to whatever runs inside.
   */
  public static String nameOf(AgentEffect effect) {
    return switch (effect) {
      case AgentEffect.Infer _ -> EFFECT + " infer";
      case AgentEffect.Approve(_, _, _, ToolName toolName) ->
          EFFECT + " approve " + toolName.value();
      case AgentEffect.CallTool(_, _, _, ToolName toolName) ->
          EFFECT + " call_tool " + toolName.value();
    };
  }
}
