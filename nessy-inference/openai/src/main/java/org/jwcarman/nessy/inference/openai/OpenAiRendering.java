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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.inference.InferenceContext;

/**
 * The words both OpenAI wires send for the parts of a conversation that are text: the tagged
 * sections of memory, state and ambient, a summary, a tool outcome, and the lines that stand where
 * a turn produced nothing. Each wire decides where these go; this class decides only what they say,
 * so the two projections cannot drift apart.
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

  private static final String BLANK_LINE = "\n\n";

  private OpenAiRendering() {}

  /**
   * What leads the active turn's user message: the memory, then the state, each section tagged with
   * its kind and separated by blank lines. Empty when there is nothing to say.
   *
   * <p><b>Where it goes is why it is here.</b> Memory is chosen for the turn being answered and
   * state is fixed for it, so both are constant across every call the turn makes. At the head of
   * the turn's own question they sit after all the history, which never changes, and ahead of
   * everything that grows during the turn, so the provider's cache covers them. In the system text
   * they would sit ahead of every message, and any change would invalidate the cache for the whole
   * conversation.
   *
   * <p>The kind is safe to interpolate without escaping -- {@code Memory} and {@code State}
   * constrain it to lowercase kebab-case precisely so no adapter has to remember to, and none can
   * forget. A section whose text is blank is left out: a label with nothing under it tells a model
   * its notes are empty, which is a claim, and saying nothing is not.
   */
  static String leading(InferenceContext context) {
    Stream<Optional<String>> recalled =
        context.memory().stream()
            .map(memory -> section("memory", kindAttribute(memory.kind()), text(memory.content())));
    Stream<Optional<String>> standing =
        context.state().stream()
            .map(state -> section("state", kindAttribute(state.kind()), text(state.content())));
    return Stream.concat(recalled, standing)
        .flatMap(Optional::stream)
        .collect(Collectors.joining(BLANK_LINE));
  }

  /**
   * What ends the request: every ambient, tagged with its kind and separated by blank lines. Empty
   * when there is nothing to say.
   *
   * <p>Ambient can change while the agent works and is asked afresh on every call, so it goes last,
   * after everything this call has in common with the one before it. The kind is safe to
   * interpolate for the same reason as in {@link #leading}, and a blank section is left out for the
   * same reason.
   */
  static String trailing(InferenceContext context) {
    return context.ambient().stream()
        .map(ambient -> section(ambient.kind(), "", text(ambient.content())))
        .flatMap(Optional::stream)
        .collect(Collectors.joining(BLANK_LINE));
  }

  /**
   * The text of a turn's opening user message once the strata around it are added: the leading
   * sections, the question, and the trailing ones, separated by blank lines. The question is always
   * there, exactly as the turn stored it; the other two are there only when they say something.
   */
  static String opening(String leading, String question, String trailing) {
    List<String> parts = new ArrayList<>();
    if (!leading.isEmpty()) {
      parts.add(leading);
    }
    parts.add(question);
    if (!trailing.isEmpty()) {
      parts.add(trailing);
    }
    return String.join(BLANK_LINE, parts);
  }

  /** {@code <tag attributes>}, the text, {@code </tag>}; nothing at all when the text is blank. */
  private static Optional<String> section(String tag, String attributes, String text) {
    if (text.isBlank()) {
      return Optional.empty();
    }
    return Optional.of("<%s%s>\n%s\n</%s>".formatted(tag, attributes, text, tag));
  }

  private static String kindAttribute(String kind) {
    return " kind=\"" + kind + "\"";
  }

  /** A summary's text, tagged with the turn range it stands for. */
  static String summary(Summary summary) {
    return "<summary from=\"%d\" through=\"%d\">\n%s\n</summary>"
        .formatted(
            summary.chapter().from().value(), summary.chapter().through().value(), summary.text());
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
