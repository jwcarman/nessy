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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
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
 * <p><b>Never throws:</b> every failure this critic can suffer on its own account — a resolver that
 * throws, a model call that errors, a response that fails to parse, a notebook write that conflicts
 * — is caught in {@link #accept}, logged at {@code WARN} naming the conversation, and dropped. The
 * settling drive this listener rides on must never see an exception out of reflection (design of
 * record §3: "a conversation failed over its own homework is worse" than a lost lesson) — the
 * opposite of the completions listener's throw-for-retry, deliberately.
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

  /** The one side model call: the settled transcript plus its outcome in, raw text out. */
  private String critique(ConversationId id, ConversationSettled settled) {
    List<Message> transcriptMessages =
        TranscriptTrim.withoutOpenTail(
            transcript.all(id).stream().map(Transcript.Entry::message).toList());
    List<Message> messages = new ArrayList<>(transcriptMessages.size() + 1);
    messages.addAll(transcriptMessages);
    messages.add(Message.user(outcomeLine(settled)));
    ModelRequest request =
        new ModelRequest(
            Context.of(messages), prompt, model, MAX_TOKENS, List.of(), Set.of(), null);
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

  private static String outcomeLine(ConversationSettled settled) {
    return settled.status() == ConversationStatus.FAILED
        ? "This conversation FAILED. Reason: " + settled.failureReason()
        : "This conversation COMPLETED successfully.";
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
