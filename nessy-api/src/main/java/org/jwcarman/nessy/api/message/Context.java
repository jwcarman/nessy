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
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * A wire-safe slice of the conversation: a list of {@link Message}s that a provider will always
 * accept, because the tool-pairing invariant is enforced on the way in rather than hoped for on the
 * way out.
 *
 * <p>The pairing invariant: for every {@link Role#ASSISTANT} message containing {@link
 * ToolUseBlock}s, the message immediately following it must be a {@link Role#USER} message whose
 * {@link ToolResultBlock}s answer exactly that set of ids — every id answered, no unknown ids, and
 * nothing in between. A {@link ToolResultBlock} may appear only in such an answering message. A
 * trailing assistant message with unanswered tool-use ids is rejected: a {@code Context} is
 * wire-bound, so an open tail belongs in {@code ConversationState}, never here.
 *
 * <p><b>The edit algebra (§10.8).</b> {@code Context} owns not just the pairing invariant but the
 * safe edits over it — raw list surgery is where pairing bugs breed, so user code never does any.
 * Every verb returns a new validated {@code Context}; bare verb names (JDK-immutable style: {@code
 * String.strip}, {@code Stream.filter}), never {@code with}-prefixes. {@link #drop}, {@link #map},
 * and {@link #enrich} are the trusted kernel — the only code that touches the message list
 * directly. {@link #elideToolResults}, {@link #keepRecent}, and {@link #limitTokens} are structural
 * verbs built on that kernel, demonstrating its sufficiency. The admission rule: a verb joins
 * {@code Context} only if its correctness depends on the context's own structure — pairing,
 * position, size — never for anything semantic. Redaction, summarization, and reordering are
 * deliberately not verbs here; compose redaction from {@link #map}/{@link #drop}, reach for a
 * custom {@code Memory} implementation for summarization, and treat reordering as inexpressible on
 * purpose, because order is meaning.
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

  /**
   * Validates the pairing invariant starting at index {@code i} and returns the index of the next
   * unvalidated message — {@code i + 1} for a plain message, {@code i + 2} once a {@code tool_use}
   * message and its answering results message both check out. Split out of the canonical
   * constructor to keep its own cognitive complexity within the house limit (S3776); the checks
   * themselves are unchanged.
   */
  private static int validatePairingFrom(List<Message> messages, int i) {
    List<String> callIds = toolUseIdsOf(messages.get(i));
    if (callIds.isEmpty()) {
      requireNoStrayResult(messages.get(i));
      return i + 1;
    }
    requireAnsweredBy(messages, i, callIds);
    return i + 2;
  }

  private static void requireNoStrayResult(Message message) {
    List<String> strayResultIds = toolResultIdsOf(message);
    if (!strayResultIds.isEmpty()) {
      throw new IllegalArgumentException(
          "tool_result outside an answering message: " + strayResultIds.getFirst());
    }
  }

  private static void requireAnsweredBy(List<Message> messages, int i, List<String> callIds) {
    if (i + 1 >= messages.size()) {
      throw new IllegalArgumentException("unanswered tool_use: " + callIds.getFirst());
    }
    Message next = messages.get(i + 1);
    if (next.role() != Role.USER) {
      throw new IllegalArgumentException("unanswered tool_use: " + callIds.getFirst());
    }
    List<String> resultIds = toolResultIdsOf(next);
    for (String callId : callIds) {
      if (!resultIds.contains(callId)) {
        throw new IllegalArgumentException("unanswered tool_use: " + callId);
      }
    }
    for (String resultId : resultIds) {
      if (!callIds.contains(resultId)) {
        throw new IllegalArgumentException("tool_result for an unknown id: " + resultId);
      }
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
   * The largest index {@code cut <= messages.size() - keepRecentMessages} at which {@code
   * messages.get(cut)} is a genuine user turn — a {@link Role#USER} message whose blocks are all
   * {@link TextBlock}s, never a spot between an assistant {@code tool_use} and the message carrying
   * its results. Walks downward from the limit (clamped to {@code messages.size() - 1} so a {@code
   * keepRecentMessages} of {@code 0} still indexes a real message); {@code 0} when no index
   * qualifies, which tells the caller nothing is safe to compact away.
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
   * matches either half of a tool exchange (the assistant message carrying {@code tool_use} blocks,
   * or the message answering it with {@code tool_result} blocks), the whole exchange goes, never
   * just one half — an unanswered {@code tool_use} or a stray {@code tool_result} is
   * unconstructible, so leaving one half behind is never an option. A plain message (no {@code
   * tool_use}, not an answering message) drops on its own when matched. The result is minted
   * through the validating constructor, which is the belt to this method's braces: a {@code
   * Context} built by construction this way is already valid. Dropping every message is legal — an
   * empty {@code Context} is a valid one — but no provider will accept an empty message list, so
   * that is the caller's problem, not this method's.
   */
  public Context drop(Predicate<Message> predicate) {
    Objects.requireNonNull(predicate, "predicate must not be null");
    List<Message> kept = new ArrayList<>(messages.size());
    int i = 0;
    while (i < messages.size()) {
      Message current = messages.get(i);
      List<String> callIds = toolUseIdsOf(current);
      if (!callIds.isEmpty()) {
        // Validated by construction: a tool_use message is always immediately followed by its
        // answering results message.
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
   * on to index its own rewrite by position. A rewrite that breaks the pairing invariant (renaming
   * a {@code tool_use} id without renaming its answering {@code tool_result}, for example)
   * propagates the validating constructor's {@link IllegalArgumentException}, naming the orphaned
   * id — {@code map} never swallows that failure.
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
   * Appends exactly ONE {@link Role#USER} message carrying {@code blocks} — the carrier for
   * non-human content, the same way {@link ToolResultBlock}s ride a user-role message. {@code
   * blocks} must be neither null nor empty: enrichment with nothing to add is a caller bug, not a
   * no-op to absorb silently.
   */
  public Context enrich(ContentBlock... blocks) {
    Objects.requireNonNull(blocks, "blocks must not be null");
    return enrich(List.of(blocks));
  }

  /**
   * Appends exactly ONE {@link Role#USER} message carrying {@code blocks} — the carrier for
   * non-human content, the same way {@link ToolResultBlock}s ride a user-role message. {@code
   * blocks} must be neither null nor empty: enrichment with nothing to add is a caller bug, not a
   * no-op to absorb silently.
   */
  public Context enrich(List<ContentBlock> blocks) {
    Objects.requireNonNull(blocks, "blocks must not be null");
    if (blocks.isEmpty()) {
      throw new IllegalArgumentException("blocks must not be empty");
    }
    List<Message> appended = new ArrayList<>(messages.size() + 1);
    appended.addAll(messages);
    appended.add(Message.user(List.copyOf(blocks)));
    return new Context(appended);
  }

  /**
   * Replaces the content of every {@link ToolResultBlock} in every message older than the last
   * {@code keepRecentMessages} messages with the placeholder {@code "[elided]"}, keeping the recent
   * window verbatim. Ids, pairing, error flags, and every other block are untouched; a message with
   * no tool results at all is returned unchanged (same instance) whether or not it falls inside the
   * elided window. Built on {@link #map}, indexing the rewrite by position — proof that the kernel
   * suffices for this shape of edit.
   *
   * @param keepRecentMessages how many of the most recent messages survive untouched; must be at
   *     least 0
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
   * The nearest pair-safe boundary that leaves at least the last {@code n} messages intact — the
   * head up to {@link #pairSafeCut(int) pairSafeCut(n)} is dropped, the tail (which may run longer
   * than {@code n} when the boundary has to walk past a tool exchange) survives. When no pair-safe
   * boundary exists short of the whole context, {@code this} is returned unchanged — there is
   * nothing safe to cut, so nothing is cut.
   *
   * @param n how many of the most recent messages must survive; must be at least 0
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

  /**
   * Drops the head, one pair-safe boundary at a time — the SMALLEST boundary that makes progress,
   * never the largest — for as long as {@code estimator}'s summed {@link #tokens(TokenEstimator)}
   * exceeds {@code budget} AND a pair-safe boundary still exists. Cutting minimally each iteration
   * keeps as much of the context as the budget allows: {@link #pairSafeCut(int) pairSafeCut(0)}
   * finds the LARGEST droppable prefix, which sheds far more than necessary to clear a budget
   * exceeded by only a little; walking up from the front instead finds the nearest genuine user
   * turn, drops only that much, and re-checks. When the boundaries run out before the budget does,
   * the result is returned over budget, honestly — {@code limitTokens} never fabricates a cut that
   * would break pairing to hit the number. Sums the whole context once, then subtracts each dropped
   * boundary's own estimates as it goes, rather than re-summing the shrinking remainder every
   * iteration — every message is estimated at most twice (once in the initial sum, once more only
   * if it is later dropped), never once per remaining iteration, even though the number of
   * iterations itself grows with the number of boundaries crossed.
   *
   * @param budget the token ceiling; must be at least 1
   * @param estimator the message-level token figure models never report
   */
  public Context limitTokens(long budget, TokenEstimator estimator) {
    if (budget < 1) {
      throw new IllegalArgumentException("budget must be at least 1");
    }
    Objects.requireNonNull(estimator, "estimator must not be null");
    Context current = this;
    long remaining = tokens(estimator);
    while (remaining > budget) {
      int cut = smallestPairSafeCut(current.messages);
      if (cut == 0) {
        break;
      }
      for (int i = 0; i < cut; i++) {
        remaining -= estimator.estimate(current.messages.get(i));
      }
      current = new Context(current.messages.subList(cut, current.messages.size()));
    }
    return current;
  }

  /**
   * The smallest index {@code cut > 0} at which {@code messages.get(cut)} is a genuine user turn —
   * the mirror image of {@link #pairSafeCut(int)}, which finds the largest such index. Walks upward
   * from {@code 1} (index {@code 0} can never be a legal cut point: cutting there would drop
   * nothing); {@code 0} when no index qualifies, matching {@link #pairSafeCut(int)}'s convention
   * for "nothing is safe to cut here."
   */
  private static int smallestPairSafeCut(List<Message> messages) {
    int limit = messages.size() - 1;
    for (int cut = 1; cut <= limit; cut++) {
      if (isGenuineUserTurn(messages.get(cut))) {
        return cut;
      }
    }
    return 0;
  }

  /** The sum of {@code estimator.estimate(message)} over every message in this context. */
  public long tokens(TokenEstimator estimator) {
    Objects.requireNonNull(estimator, "estimator must not be null");
    long total = 0;
    for (Message message : messages) {
      total += estimator.estimate(message);
    }
    return total;
  }

  /** One line of the transcript this context renders as: who said it, and what they said. */
  public record Line(String role, String text) {}

  /**
   * The transcript this context renders as, in message order: one {@link Line} per message that has
   * any text.
   *
   * <p>A message's {@link TextBlock}s join into one string, in order; every other block kind —
   * thinking, redacted thinking, tool use, tool results — is invisible here, on purpose: this is
   * the chat log, not the trace. A message with no {@code TextBlock}s (a pure tool-results message,
   * an empty turn) contributes nothing rather than an empty {@link Line}.
   */
  public List<Line> lines() {
    List<Line> lines = new ArrayList<>();
    for (Message message : messages) {
      String text = textOf(message);
      if (!text.isEmpty()) {
        lines.add(new Line(message.role().name().toLowerCase(Locale.ROOT), text));
      }
    }
    return lines;
  }

  private static String textOf(Message message) {
    StringBuilder text = new StringBuilder();
    for (ContentBlock block : message.content()) {
      if (block instanceof TextBlock(String blockText)) {
        text.append(blockText);
      }
    }
    return text.toString();
  }

  private static Message elideToolResultContent(Message message) {
    List<ContentBlock> content = message.content();
    boolean hasToolResult = content.stream().anyMatch(ToolResultBlock.class::isInstance);
    if (!hasToolResult) {
      return message;
    }
    List<ContentBlock> elided = new ArrayList<>(content.size());
    for (ContentBlock block : content) {
      elided.add(
          block instanceof ToolResultBlock toolResult
              ? ToolResultBlock.of(toolResult.toolUseId(), "[elided]", toolResult.isError())
              : block);
    }
    return new Message(message.role(), elided);
  }

  private static boolean isGenuineUserTurn(Message message) {
    return message.role() == Role.USER
        && !message.content().isEmpty()
        && message.content().stream().allMatch(TextBlock.class::isInstance);
  }

  private static List<String> toolUseIdsOf(Message message) {
    if (message.role() != Role.ASSISTANT) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (ContentBlock block : message.content()) {
      if (block instanceof ToolUseBlock(ToolCall call, _)) {
        ids.add(call.id());
      }
    }
    return ids;
  }

  private static List<String> toolResultIdsOf(Message message) {
    List<String> ids = new ArrayList<>();
    for (ContentBlock block : message.content()) {
      if (block instanceof ToolResultBlock toolResult) {
        ids.add(toolResult.toolUseId());
      }
    }
    return ids;
  }
}
