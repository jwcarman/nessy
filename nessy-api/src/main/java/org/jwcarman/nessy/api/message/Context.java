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
package org.jwcarman.nessy.api.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.block.UserContentBlock;

/**
 * A wire-safe slice of the conversation: a list of {@link Message}s that a provider will always
 * accept, because the tool-pairing invariant is enforced on the way in rather than hoped for on the
 * way out.
 *
 * <p>The pairing invariant: for every {@link AnswerMessage} containing {@link ToolCallBlock}s, the
 * message immediately following it must be a {@link ToolResultMessage} answering exactly that set
 * of ids — every id answered, no unknown ids, and nothing in between. A {@link ToolResultMessage}
 * may appear only as such an answer. A trailing assistant message with unanswered call ids is
 * rejected: a {@code Context} is wire-bound, so an open tail belongs in the agent's own state,
 * never here.
 *
 * <p><b>What the type system took over.</b> Two checks this class used to perform are now
 * unwritable rather than validated: a tool result outside an answering message (a {@link
 * ToolResultBlock} is legal only inside a {@link ToolResultMessage}), and an answering message with
 * the wrong role (it is its own type). What remains is what no type can express — that the id sets
 * match, and that the two messages are adjacent.
 *
 * <p><b>The edit algebra.</b> {@code Context} owns not just the pairing invariant but the safe
 * edits over it — raw list surgery is where pairing bugs breed, so user code never does any. Every
 * verb returns a new validated {@code Context}; bare verb names (JDK-immutable style: {@code
 * String.strip}, {@code Stream.filter}), never {@code with}-prefixes. {@link #drop}, {@link #map},
 * and {@link #enrich} are the trusted kernel — the only code that touches the message list
 * directly. {@link #elideToolResults} and {@link #keepRecent} are structural verbs built on that
 * kernel, demonstrating its sufficiency. The admission rule: a verb joins {@code Context} only if
 * its correctness depends on the context's own structure — pairing, position, size — never for
 * anything semantic. Redaction, summarization, and reordering are deliberately not verbs here;
 * compose redaction from {@link #map}/{@link #drop}, reach for a custom memory implementation for
 * summarization, and treat reordering as inexpressible on purpose, because order is meaning.
 */
public record Context(List<ContextMessage> messages) {

  public Context {
    Objects.requireNonNull(messages, "messages must not be null");
    messages = List.copyOf(messages);
  }

  public static Context of(List<ContextMessage> messages) {
    return new Context(messages);
  }

  /** The empty context — no messages, trivially valid. */
  public static Context empty() {
    return Context.of(List.of());
  }

  /**
   * The prefix {@code [0, cut)} as a new {@code Context}. {@code cut} must come from {@link
   * #pairSafeCut}.
   */
  public Context head(int cut) {
    return new Context(messages.subList(0, cut));
  }

  /**
   * Drops every message matched by {@code predicate} — pair-atomically. If {@code predicate}
   * matches either half of a tool exchange, the whole exchange goes, never just one half: an
   * unanswered call or a stray result message is unconstructible, so leaving one half behind is
   * never an option. A plain message drops on its own when matched. The result is minted through
   * the validating constructor, which is the belt to this method's braces. Dropping every message
   * is legal — an empty {@code Context} is a valid one — but no provider will accept an empty
   * message list, so that is the caller's problem, not this method's.
   */
  public Context drop(Predicate<ContextMessage> predicate) {
    Objects.requireNonNull(predicate, "predicate must not be null");
    List<ContextMessage> kept = new ArrayList<>(messages.size());
    for (ContextMessage message : messages) {
      if (!predicate.test(message)) {
        kept.add(message);
      }
    }
    return new Context(kept);
  }

  /**
   * Rewrites every message with {@code rewriter}, then revalidates the result. Applies {@code
   * rewriter} exactly once per message, in list order — a contract {@link #elideToolResults} relies
   * on to index its own rewrite by position. A rewrite that breaks the pairing invariant propagates
   * the validating constructor's {@link IllegalArgumentException}, naming the orphaned id; {@code
   * map} never swallows that failure.
   *
   * @throws NullPointerException if {@code rewriter} is null, or if it returns a null message
   */
  public Context map(UnaryOperator<ContextMessage> rewriter) {
    Objects.requireNonNull(rewriter, "rewriter must not be null");
    List<ContextMessage> rewritten = new ArrayList<>(messages.size());
    for (ContextMessage message : messages) {
      ContextMessage result = rewriter.apply(message);
      Objects.requireNonNull(result, "rewriter must not return a null message");
      rewritten.add(result);
    }
    return new Context(rewritten);
  }

  /**
   * Appends exactly ONE {@link UserMessage} carrying {@code blocks} — the carrier for non-human
   * content. {@code blocks} must be neither null nor empty: enrichment with nothing to add is a
   * caller bug, not a no-op to absorb silently.
   */
  public Context enrich(UserContentBlock... blocks) {
    Objects.requireNonNull(blocks, "blocks must not be null");
    return enrich(List.of(blocks));
  }

  /** See {@link #enrich(UserContentBlock...)}. */
  public Context enrich(List<UserContentBlock> blocks) {
    Objects.requireNonNull(blocks, "blocks must not be null");
    if (blocks.isEmpty()) {
      throw new IllegalArgumentException("blocks must not be empty");
    }
    List<ContextMessage> appended = new ArrayList<>(messages.size() + 1);
    appended.addAll(messages);
    appended.add(new UserMessage(List.copyOf(blocks)));
    return new Context(appended);
  }

  /**
   * Replaces the content of every {@link ToolResultBlock} older than the last {@code
   * keepRecentMessages} messages with the placeholder {@code "[elided]"}, keeping the recent window
   * verbatim. Ids, pairing, error flags, and every other block are untouched; a message with no
   * tool results is returned unchanged whether or not it falls inside the elided window.
   *
   * @param keepRecentMessages how many of the most recent messages survive untouched; at least 0
   */
  public Context elideToolResults(int keepRecentMessages) {
    if (keepRecentMessages < 0) {
      throw new IllegalArgumentException("keepRecentMessages must be at least 0");
    }
    int firstRecentIndex = Math.max(0, messages.size() - keepRecentMessages);
    int[] index = {0};
    return map(
        message -> {
          int i = index[0]++;
          return i < firstRecentIndex ? elideToolResultContent(message) : message;
        });
  }

  /**
   * @param n how many of the most recent messages must survive; at least 0
   */
  public Context keepRecent(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("n must be at least 0");
    }
    if (n >= messages.size()) {
      return this;
    }
    // A plain cut: an ExchangeMessage carries its own results, so there is no pair to land
    // between.
    return new Context(messages.subList(messages.size() - n, messages.size()));
  }

  /** One line of the transcript this context renders as: who said it, and what they said. */
  public record Line(String role, String text) {}

  /**
   * The transcript this context renders as, in message order: one {@link Line} per message that has
   * any text.
   *
   * <p>A message's {@link TextBlock}s join into one string, in order; every other block kind —
   * thinking, redacted thinking, tool calls, tool results — is invisible here, on purpose: this is
   * the chat log, not the trace. A message with no text contributes nothing rather than an empty
   * {@link Line}.
   */
  public List<Line> lines() {
    List<Line> lines = new ArrayList<>();
    for (ContextMessage message : messages) {
      String text = textOf(message);
      if (!text.isEmpty()) {
        lines.add(new Line(roleOf(message), text));
      }
    }
    return lines;
  }

  /** Who a line came from. Background is nobody's speech, so it never becomes one. */
  private static String roleOf(ContextMessage message) {
    return message instanceof AnswerMessage || message instanceof ExchangeMessage
        ? "assistant"
        : "user";
  }

  /**
   * The visible text of one message.
   *
   * <p>An {@link ExchangeMessage} contributes its commentary — the model saying what it is about to
   * do, which a reader watched arrive and expects to still be there. An {@link AmbientMessage}
   * contributes nothing: it is background assembled for the model, not something anyone said.
   */
  private static String textOf(ContextMessage message) {
    StringBuilder text = new StringBuilder();
    switch (message) {
      case UserMessage(var content) ->
          content.stream()
              .filter(TextBlock.class::isInstance)
              .forEach(block -> text.append(((TextBlock) block).text()));
      case AnswerMessage(var content) ->
          content.stream()
              .filter(TextBlock.class::isInstance)
              .forEach(block -> text.append(((TextBlock) block).text()));
      case ExchangeMessage asking ->
          asking.content().stream()
              .filter(CommentaryBlock.class::isInstance)
              .forEach(block -> text.append(((CommentaryBlock) block).text()));
      case AmbientMessage _ -> {
        // Background is not speech.
      }
    }
    return text.toString();
  }

  /**
   * Replaces a settled exchange's result content with a placeholder, keeping the exchange itself.
   *
   * <p>The calls stay, their ids stay, and the answers become {@code [elided]} — a shape the
   * provider still accepts, because the pairing is intact by construction rather than by care.
   */
  private static ContextMessage elideToolResultContent(ContextMessage message) {
    if (!(message instanceof ExchangeMessage(var content, var results)) || results.isEmpty()) {
      return message;
    }
    List<ToolResultBlock> elided = new ArrayList<>(results.size());
    for (ToolResultBlock block : results) {
      elided.add(
          new ToolResultBlock(
              block.toolUseId(), List.of(new TextBlock("[elided]")), block.isError()));
    }
    return new ExchangeMessage(content, elided);
  }
}
