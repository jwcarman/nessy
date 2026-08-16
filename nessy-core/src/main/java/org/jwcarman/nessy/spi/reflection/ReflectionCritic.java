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
package org.jwcarman.nessy.spi.reflection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.notebook.Notebook;
import org.jwcarman.nessy.spi.transcript.Transcript;
import org.jwcarman.nessy.spi.transcript.TranscriptTrim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The critic itself: a {@code Consumer<ConversationSettled>} that, on a trigger settlement, makes
 * one side model call over the settled transcript and writes 0..n lessons into the subject's {@link
 * Notebook} (design of record 2026-08-16 §3). Built only by {@link ReflectionBuilder#build()}.
 *
 * <p><b>Trigger:</b> {@code FAILED} always; {@code COMPLETE} only when {@code reflectOnSuccess} is
 * {@code true}. {@code PARKED} is never published as a {@link ConversationSettled} in the first
 * place (see that record's javadoc), so this consumer never sees one.
 *
 * <p><b>The side call renders to plain text, not raw messages:</b> the settled transcript's {@link
 * Message}s are never replayed verbatim into the critic's {@link ModelRequest} — that request
 * declares no tools, and a transcript carrying a {@link ToolUseBlock}/{@link ToolResultBlock} pair
 * (or a signed {@link ThinkingBlock}) is illegal history for a tools-less call on at least one
 * major provider (Anthropic 400s it), which would silently kill reflection for exactly the
 * tool-using-and-failed case this feature exists for. Instead {@link #renderTranscript} folds the
 * whole transcript into one role-labeled prose block — a tool call and its result collapse to one
 * line, thinking is omitted — sent as a single user message: the critic wants something to
 * critique, not a resumable conversation.
 *
 * <p><b>Runs inside the settling drive:</b> this consumer is registered as a synchronous listener
 * ({@link org.jwcarman.nessy.HarnessConfig#listen}), so a {@code FAILED} {@code tell} does not
 * return to its caller until the critic's model call (and the notebook writes it triggers) has
 * finished — reflection's token spend and latency are on the critical path of the conversation that
 * triggered it. An app that cannot afford that latency wires the critic through {@link
 * org.jwcarman.nessy.HarnessConfig#listenAsync(Class, Consumer)} instead, at a cost: the critique
 * then runs on its own virtual thread, off the drive that returned, and a short-lived process (a
 * CLI invocation, a Lambda) can exit before that thread finishes — no lesson lands at all,
 * silently, which a purely synchronous critic never risks. A transcript long enough to exceed the
 * critic model's own context window is not truncated here; the provider's own error surfaces as any
 * other model-call failure this critic catches — logged at {@code WARN}, no lesson written.
 *
 * <p><b>Never throws:</b> every failure this critic can suffer on its own account — a resolver that
 * throws, a model call that errors, a response that fails to parse, a notebook write that conflicts
 * — is caught in {@link #accept}, logged at {@code WARN} naming the conversation, and dropped. The
 * settling drive this listener rides on must never see an exception out of reflection (design of
 * record §3: "a conversation failed over its own homework is worse" than a lost lesson) — the
 * opposite of the completions listener's throw-for-retry, deliberately.
 *
 * <p><b>{@code "reflection"} and the {@code lesson:} prefix are conventions, not enforced
 * boundaries:</b> {@code NotebookTools}' authorship gate matches on the identity string alone
 * (design of record 2026-08-16 §2, the grant principle) — an app that wires an ordinary agent's
 * {@code NotebookTools} with the identity {@code "reflection"} gains the very same mutate rights
 * over every lesson this critic writes as the critic itself. Nothing here reserves that identity or
 * the {@code lesson:} name prefix; keeping them out of an app's own agent wiring is a convention
 * this feature relies on, not one it guards against.
 */
final class ReflectionCritic implements Consumer<ConversationSettled> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReflectionCritic.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_TOKENS = 2048;
  private static final String SOURCE = "reflection";

  private final Transcript transcript;
  private final Notebook notebook;
  private final Function<ConversationId, SubjectId> subject;
  private final ModelProvider provider;
  private final String model;
  private final String prompt;
  private final boolean reflectOnSuccess;

  ReflectionCritic(
      Transcript transcript,
      Notebook notebook,
      Function<ConversationId, SubjectId> subject,
      ModelProvider provider,
      String model,
      String prompt,
      boolean reflectOnSuccess) {
    this.transcript = transcript;
    this.notebook = notebook;
    this.subject = subject;
    this.provider = provider;
    this.model = model;
    this.prompt = prompt;
    this.reflectOnSuccess = reflectOnSuccess;
  }

  @Override
  public void accept(ConversationSettled settled) {
    Objects.requireNonNull(settled, "settled must not be null");
    if (settled.status() == ConversationStatus.COMPLETE && !reflectOnSuccess) {
      return;
    }
    try {
      reflect(settled);
    } catch (RuntimeException e) {
      LOGGER.warn(
          "reflection failed for conversation {}; dropping this critique",
          settled.conversationId().value(),
          e);
    }
  }

  private void reflect(ConversationSettled settled) {
    ConversationId id = settled.conversationId();
    SubjectId subjectId = subject.apply(id);
    if (subjectId == null) {
      return;
    }
    String response = critique(id, settled);
    List<Notebook.Entry> lessons = parseLessons(id, response);
    for (Notebook.Entry lesson : lessons) {
      notebook.save(subjectId, lesson);
    }
  }

  /**
   * The one side model call: the settled transcript, rendered to plain text, plus its outcome, sent
   * as a single {@code USER} message — never the raw {@link Message} list (see this class's own
   * javadoc for why). No tools are declared on this request; the critic reviews, it doesn't act.
   */
  private String critique(ConversationId id, ConversationSettled settled) {
    List<Message> transcriptMessages =
        TranscriptTrim.withoutOpenTail(
            transcript.all(id).stream().map(Transcript.Entry::message).toList());
    String userMessage = renderTranscript(transcriptMessages) + outcomeLine(settled);
    ModelRequest request =
        new ModelRequest(
            Context.of(List.of(Message.user(userMessage))),
            prompt,
            model,
            MAX_TOKENS,
            List.of(),
            Set.of(),
            null);
    StringBuilder text = new StringBuilder();
    try (ModelStream stream = provider.stream(request)) {
      for (ModelEvent event : stream) {
        if (event instanceof ModelEvent.TextChunk(String chunk)) {
          text.append(chunk);
        }
      }
    }
    return text.toString();
  }

  /**
   * Folds {@code messages} into role-labeled prose, one line per block: a {@link TextBlock} becomes
   * {@code "User: ..."} or {@code "Assistant: ..."}; a {@link ToolUseBlock} becomes {@code
   * "Assistant: called <name>(<arguments>) → <result>"}, its matching {@link ToolResultBlock}
   * (found by {@link ToolCall#id()}, wherever in the transcript it landed) folded into the same
   * line rather than rendered as its own turn; {@link ThinkingBlock}, {@link
   * RedactedThinkingBlock}, and {@link ImageBlock} carry nothing a text critique can use and are
   * silently omitted. The critic wants a conversation to critique, not a resumable one.
   */
  static String renderTranscript(List<Message> messages) {
    Map<String, ToolResultBlock> resultsByCallId = collectToolResults(messages);
    StringBuilder rendered = new StringBuilder();
    for (Message message : messages) {
      String role = message.role() == Role.USER ? "User" : "Assistant";
      for (ContentBlock block : message.content()) {
        appendBlock(rendered, role, block, resultsByCallId);
      }
    }
    return rendered.toString();
  }

  /** The {@link ToolResultBlock}s a call's line folds in, indexed by the call's own id. */
  private static Map<String, ToolResultBlock> collectToolResults(List<Message> messages) {
    Map<String, ToolResultBlock> resultsByCallId = new LinkedHashMap<>();
    for (Message message : messages) {
      for (ContentBlock block : message.content()) {
        if (block instanceof ToolResultBlock result) {
          resultsByCallId.put(result.toolUseId(), result);
        }
      }
    }
    return resultsByCallId;
  }

  /** Renders one {@code block} as its own line onto {@code rendered}, or nothing at all. */
  private static void appendBlock(
      StringBuilder rendered,
      String role,
      ContentBlock block,
      Map<String, ToolResultBlock> resultsByCallId) {
    switch (block) {
      case TextBlock(String text) when !text.isBlank() ->
          rendered.append(role).append(": ").append(text).append('\n');
      case TextBlock _ -> {
        // Blank text carries nothing a text critique needs.
      }
      case ToolUseBlock toolUse ->
          rendered
              .append(role)
              .append(": ")
              .append(renderToolUse(toolUse, resultsByCallId))
              .append('\n');
      case ToolResultBlock _ -> {
        // Folded into its call's line above, not rendered as its own turn.
      }
      case ThinkingBlock _ -> {
        // The model's visible reasoning carries nothing a text critique needs.
      }
      case RedactedThinkingBlock _ -> {
        // Opaque to everyone but the provider that issued it.
      }
      case ImageBlock _ -> {
        // No textual content to fold into a prose critique.
      }
    }
  }

  private static String renderToolUse(
      ToolUseBlock toolUse, Map<String, ToolResultBlock> resultsByCallId) {
    ToolCall call = toolUse.call();
    ToolResultBlock result = resultsByCallId.get(call.id());
    String outcome = result == null ? "(no result recorded)" : renderResult(result);
    return "called " + call.name() + "(" + call.arguments() + ") → " + outcome;
  }

  /** The result half of a call's line: an {@code error: } prefix only when the call failed. */
  private static String renderResult(ToolResultBlock result) {
    String prefix = result.isError() ? "error: " : "";
    return prefix + result.content();
  }

  private static String outcomeLine(ConversationSettled settled) {
    if (settled.status() != ConversationStatus.FAILED) {
      return "This conversation COMPLETED successfully.";
    }
    String reason = settled.failureReason();
    return reason == null
        ? "This conversation FAILED."
        : "This conversation FAILED. Reason: " + reason;
  }

  /**
   * Parses the critic's response leniently: a fenced ```json (or bare ```) code block wrapping the
   * array is unwrapped first. A response that is not valid JSON, or not a JSON array, logs one
   * {@code WARN} naming the conversation and yields no lessons — the parse failure never throws out
   * of this method.
   */
  private List<Notebook.Entry> parseLessons(ConversationId id, String response) {
    String json = stripFence(response);
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      LOGGER.warn(
          "critic response for conversation {} was not valid JSON; dropping it", id.value(), e);
      return List.of();
    }
    if (root == null || !root.isArray()) {
      LOGGER.warn(
          "critic response for conversation {} was not a JSON array; dropping it", id.value());
      return List.of();
    }
    List<Notebook.Entry> lessons = new ArrayList<>();
    int accepted = 0;
    for (JsonNode node : root) {
      String hook = textField(node, "hook");
      String body = textField(node, "body");
      if (hook == null || hook.isBlank() || body == null || body.isBlank()) {
        LOGGER.warn(
            "critic response for conversation {} contained a lesson missing hook/body; dropping"
                + " it",
            id.value());
        continue;
      }
      accepted++;
      lessons.add(new Notebook.Entry(lessonName(id, accepted), hook, body, SOURCE));
    }
    return lessons;
  }

  private static String textField(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  /** {@code lesson:<conversation-id>} for the first lesson, {@code -2}, {@code -3}... after. */
  private static String lessonName(ConversationId id, int position) {
    return position == 1 ? "lesson:" + id.value() : "lesson:" + id.value() + "-" + position;
  }

  /** Strips a fenced code block (```json ... ``` or ``` ... ```) wrapping the JSON, if present. */
  private static String stripFence(String response) {
    String trimmed = response.strip();
    if (!trimmed.startsWith("```")) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    int closingFence = trimmed.lastIndexOf("```");
    if (firstNewline < 0 || closingFence <= firstNewline) {
      return trimmed;
    }
    return trimmed.substring(firstNewline + 1, closingFence).strip();
  }
}
