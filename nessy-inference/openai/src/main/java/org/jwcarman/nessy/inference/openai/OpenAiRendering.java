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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.inference.InferenceRequest;

/**
 * The words both OpenAI wires send for the parts of a conversation that are text: the system prompt
 * with its ambient sections, a summary, a tool outcome, and the lines that stand where a turn
 * produced nothing. Each wire decides where these go; this class decides only what they say, so the
 * two projections cannot drift apart.
 */
final class OpenAiRendering {

  /**
   * Stands where a failed turn's answer would be. "Did not complete", because the call may never
   * have been made.
   */
  static final String FAILED_TURN = "The previous attempt to answer did not complete.";

  /**
   * Stands where a withdrawn question stood. Saying nothing would leave two user turns adjacent;
   * saying what it was would put back the very content this exists to remove.
   */
  static final String REFUSED_TURN =
      "A previous message was withdrawn from this conversation and is no longer available.";

  private OpenAiRendering() {}

  /**
   * The system prompt, and whatever background stands behind the conversation.
   *
   * <p><b>Where the background goes is each adapter's decision alone.</b> The chat adapter folds it
   * into the system message; the Responses adapter decides for itself where the prompt and the
   * background travel, as Anthropic would use its top-level system block and Gemini a system
   * instruction. {@link Ambient} says what the background is and takes no view on any of that.
   *
   * <p>Labelled with tags so a model reading two unlabelled blobs run together can tell which is
   * the standing instruction and which is today's note. The kind is safe to interpolate without
   * escaping -- {@code Ambient} constrains it to lowercase kebab-case precisely so no adapter has
   * to remember to, and none can forget.
   *
   * <p>Sections are omitted entirely when there are none. A heading with nothing under it tells a
   * model its notebook is empty, which is a claim; saying nothing is not.
   */
  static String system(InferenceRequest request) {
    if (!request.context().hasAmbient()) {
      return request.systemPrompt().value();
    }
    StringBuilder system = new StringBuilder(request.systemPrompt().value());
    for (Ambient ambient : request.context().ambient()) {
      system
          .append("\n\n<")
          .append(ambient.kind())
          .append(">\n")
          .append(text(ambient.content()))
          .append("\n</")
          .append(ambient.kind())
          .append('>');
    }
    return system.toString();
  }

  /** A summary's text, tagged with the turn range it stands for. */
  static String summary(Summary summary) {
    return "<summary from=\"%d\" through=\"%d\">\n%s\n</summary>"
        .formatted(summary.from().value(), summary.through().value(), text(summary.content()));
  }

  /**
   * One tool outcome as text. All three flatten to a string on both wires, because neither has a
   * field for "denied" or an error flag on a result.
   */
  static String outcome(ToolOutcome outcome) {
    return switch (outcome) {
      case ToolOutcome.Succeeded(CallId _, var blocks) -> text(blocks);
      case ToolOutcome.Failed(CallId _, String message) -> "Error: " + message;
      case ToolOutcome.Denied(CallId _, String reason) ->
          "This call was not run because it was not permitted: " + reason;
    };
  }

  /**
   * The text of a run of blocks, and only the text.
   *
   * <p>Exhaustive, so a new block kind has to say here whether it is something a person reads.
   * Anything that is not text contributes nothing rather than being cast and thrown: a call travels
   * as a call, not as text, and another vendor's reasoning state belongs to whoever attached it --
   * handing those bytes to this endpoint would at best be ignored and at worst rejected.
   *
   * <p>Commentary is re-sent beside the answer because it is part of what the assistant said; each
   * adapter decides where the calls and the text travel, and this renders only the text.
   */
  static String text(List<? extends Block> blocks) {
    return blocks.stream()
        .map(OpenAiRendering::readable)
        .flatMap(Optional::stream)
        .collect(Collectors.joining());
  }

  private static Optional<String> readable(Block block) {
    return switch (block) {
      case Block.Text(String value) -> Optional.of(value);
      case Block.Commentary(String value) -> Optional.of(value);
      case Block.Provider _, Block.ToolCall _ -> Optional.empty();
    };
  }
}
