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
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.block.UserContentBlock;

/**
 * A wire-safe slice of the conversation: a list of {@link Message}s that a provider will always
 * accept, because the tool-pairing invariant is enforced on the way in rather than hoped for on the
 * way out.
 *
 * <p>The pairing invariant: for every {@link AssistantMessage} containing {@link ToolCallBlock}s,
 * the message immediately following it must be a {@link ToolResultMessage} answering exactly that
 * set of ids — every id answered, no unknown ids, and nothing in between. A {@link
 * ToolResultMessage} may appear only as such an answer. A trailing assistant message with
 * unanswered call ids is rejected: a {@code Context} is wire-bound, so an open tail belongs in the
 * agent's own state, never here.
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
public record Context(List<Message> messages) {

  public Context {
    Objects.requireNonNull(messages, "messages must not be null");
    messages = List.copyOf(messages);
    int i = 0;
    while (i < messages.size()) {
      i = validatePairingFrom(messages, i);
    }
  }

  public static Context of(List<Message> messages) {
    return new Context(messages);
  }

  /** The empty context — no messages, trivially valid. */
  public static Context empty() {
    return Context.of(List.of());
  }

  /**
   * Validates the pairing invariant starting at index {@code i} and returns the index of the next
   * unvalidated message — {@code i + 1} for a plain message, {@code i + 2} once a message carrying
   * tool calls and its answering message both check out.
   */
  private static int validatePairingFrom(List<Message> messages, int i) {
    Message message = messages.get(i);
    List<String> callIds = toolCallIdsOf(message);
    if (callIds.isEmpty()) {
      if (message instanceof ToolResultMessage) {
        throw new IllegalArgumentException("tool results answering no call: index " + i);
      }
      return i + 1;
    }
    requireAnsweredBy(messages, i, callIds);
    return i + 2;
  }

  private static void requireAnsweredBy(List<Message> messages, int i, List<String> callIds) {
    if (i + 1 >= messages.size()) {
      throw new IllegalArgumentException("unanswered tool call: " + callIds.getFirst());
    }
    if (!(messages.get(i + 1) instanceof ToolResultMessage answer)) {
      throw new IllegalArgumentException("unanswered tool call: " + callIds.getFirst());
    }
    List<String> resultIds = answer.blocks().stream().map(ToolResultBlock::toolUseId).toList();
    for (String callId : callIds) {
      if (!resultIds.contains(callId)) {
        throw new IllegalArgumentException("unanswered tool call: " + callId);
      }
    }
    for (String resultId : resultIds) {
      if (!callIds.contains(resultId)) {
        throw new IllegalArgumentException("tool result for an unknown id: " + resultId);
      }
    }
  }

  private static List<String> toolCallIdsOf(Message message) {
    if (!(message instanceof AssistantMessage assistant)) {
      return List.of();
    }
    return assistant.content().stream()
        .filter(ToolCallBlock.class::isInstance)
        .map(block -> ((ToolCallBlock) block).id())
        .toList();
  }

  /**
   * The largest index {@code cut <= messages.size() - keepRecentMessages} at which {@code
   * messages.get(cut)} is a genuine user turn — never a spot between an assistant's tool calls and
   * the message carrying their results. Walks downward from the limit (clamped to {@code
   * messages.size() - 1} so a {@code keepRecentMessages} of {@code 0} still indexes a real
   * message); {@code 0} when no index qualifies, which tells the caller nothing is safe to cut.
   */
  public int pairSafeCut(int keepRecentMessages) {
    int limit = Math.min(messages.size() - keepRecentMessages, messages.size() - 1);
    for (int cut = limit; cut > 0; cut--) {
      if (isGenuineUserTurn(messages.get(cut))) {
        return cut;
      }
    }
    return 0;
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
  public Context drop(Predicate<Message> predicate) {
    Objects.requireNonNull(predicate, "predicate must not be null");
    List<Message> kept = new ArrayList<>(messages.size());
    int i = 0;
    while (i < messages.size()) {
      Message current = messages.get(i);
      if (!toolCallIdsOf(current).isEmpty()) {
        // Validated by construction: a message carrying tool calls is always immediately followed
        // by its answering message.
        Message results = messages.get(i + 1);
        if (!predicate.test(current) && !predicate.test(results)) {
          kept.add(current);
          kept.add(results);
        }
        i += 2;
        continue;
      }
      if (!predicate.test(current)) {
        kept.add(current);
      }
      i++;
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
  public Context map(UnaryOperator<Message> rewriter) {
    Objects.requireNonNull(rewriter, "rewriter must not be null");
    List<Message> rewritten = new ArrayList<>(messages.size());
    for (Message message : messages) {
      Message result = rewriter.apply(message);
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
    List<Message> appended = new ArrayList<>(messages.size() + 1);
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
   * The nearest pair-safe boundary that leaves at least the last {@code n} messages intact. When no
   * pair-safe boundary exists short of the whole context, {@code this} is returned unchanged —
   * there is nothing safe to cut, so nothing is cut.
   *
   * @param n how many of the most recent messages must survive; at least 0
   */
  public Context keepRecent(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("n must be at least 0");
    }
    int cut = pairSafeCut(n);
    if (cut == 0) {
      return this;
    }
    return new Context(messages.subList(cut, messages.size()));
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
    for (Message message : messages) {
      String text = textOf(message);
      if (!text.isEmpty()) {
        lines.add(new Line(roleOf(message), text));
      }
    }
    return lines;
  }

  private static String roleOf(Message message) {
    return message instanceof AssistantMessage ? "assistant" : "user";
  }

  private static String textOf(Message message) {
    StringBuilder text = new StringBuilder();
    if (message instanceof UserMessage user) {
      user.content().stream()
          .filter(TextBlock.class::isInstance)
          .forEach(block -> text.append(((TextBlock) block).text()));
    } else if (message instanceof AssistantMessage assistant) {
      assistant.content().stream()
          .filter(TextBlock.class::isInstance)
          .forEach(block -> text.append(((TextBlock) block).text()));
    }
    return text.toString();
  }

  private static Message elideToolResultContent(Message message) {
    if (!(message instanceof ToolResultMessage results) || results.blocks().isEmpty()) {
      return message;
    }
    List<ToolResultBlock> elided = new ArrayList<>(results.blocks().size());
    for (ToolResultBlock block : results.blocks()) {
      elided.add(
          new ToolResultBlock(
              block.toolUseId(), List.of(new TextBlock("[elided]")), block.isError()));
    }
    return new ToolResultMessage(elided);
  }

  private static boolean isGenuineUserTurn(Message message) {
    return message instanceof UserMessage user
        && !user.content().isEmpty()
        && user.content().stream().allMatch(TextBlock.class::isInstance);
  }
}
