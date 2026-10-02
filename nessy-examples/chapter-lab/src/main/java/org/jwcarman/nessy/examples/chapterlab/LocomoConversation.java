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
package org.jwcarman.nessy.examples.chapterlab;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One conversation from the LoCoMo data, read the way the lab replays it: as turns, each carrying
 * the session it belongs to, plus the questions with known answers that can be asked of it.
 *
 * <p>A recorded session is a list of messages. They are paired in order into turns: the first
 * message is what the user said and the next is the reply, so a session of five messages is three
 * turns and the last has no reply. Each input is prefixed with the session's date in brackets, so
 * the date is part of what the model reads, as it was for the probe this lab repeats.
 *
 * <p>Only questions of categories one to four that cite evidence are kept; the fifth category asks
 * about things the conversation never says.
 */
final class LocomoConversation {

  /** What stands in for the reply to a message nobody answered. */
  static final String NO_REPLY = "(no reply)";

  private static final int FIRST_CATEGORY = 1;
  private static final int LAST_CATEGORY = 4;

  /**
   * One recorded exchange.
   *
   * @param session which recorded session it belongs to, from one
   * @param input what the first speaker said, prefixed with the session's date
   * @param reply what the second speaker said, or {@link #NO_REPLY}
   */
  record Recorded(int session, String input, String reply) {}

  /**
   * A question with a known answer.
   *
   * @param category 1 multi-hop, 2 dates, 3 inference, 4 single fact
   */
  record Question(String text, String answer, int category) {}

  private final List<Recorded> turns;
  private final List<Question> questions;

  private LocomoConversation(List<Recorded> turns, List<Question> questions) {
    this.turns = List.copyOf(turns);
    this.questions = List.copyOf(questions);
  }

  /** The turns, in the order they were recorded. */
  List<Recorded> turns() {
    return turns;
  }

  /** Every question that can be asked of this conversation, in the file's order. */
  List<Question> questions() {
    return questions;
  }

  /** Whether the turn at {@code index} (from zero) is the last of its recorded session. */
  boolean endsSession(int index) {
    return index == turns.size() - 1
        || turns.get(index + 1).session() != turns.get(index).session();
  }

  /**
   * A fixed pseudo-random sample of the questions: the same {@code conversation} and {@code count}
   * always select the same ones. The seed is {@code 7 + conversation}, as in the probe, though
   * Java's generator does not reproduce Python's choice from it.
   */
  List<Question> sample(int count, int conversation) {
    List<Question> shuffled = new ArrayList<>(questions);
    Collections.shuffle(shuffled, new Random(7L + conversation));
    return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
  }

  /**
   * Reads conversation number {@code index} (from zero) out of a LoCoMo file.
   *
   * @throws IllegalArgumentException if the file has no such conversation
   */
  static LocomoConversation read(File file, int index) {
    JsonNode all = JsonMapper.builder().build().readTree(file);
    if (index < 0 || index >= all.size()) {
      throw new IllegalArgumentException(
          "conversation %d is not in %s, which holds %d".formatted(index, file, all.size()));
    }
    return of(all.get(index));
  }

  /** A conversation from its parsed JSON: an object holding {@code conversation} and {@code qa}. */
  static LocomoConversation of(JsonNode parsed) {
    return new LocomoConversation(turnsOf(parsed.path("conversation")), questionsOf(parsed));
  }

  private static List<Recorded> turnsOf(JsonNode conversation) {
    List<Recorded> turns = new ArrayList<>();
    for (int session = 1; conversation.has("session_" + session); session++) {
      JsonNode messages = conversation.get("session_" + session);
      String date = conversation.path("session_" + session + "_date_time").asString("");
      for (int i = 0; i < messages.size(); i += 2) {
        String reply = i + 1 < messages.size() ? said(messages.get(i + 1)) : NO_REPLY;
        turns.add(new Recorded(session, "[" + date + "] " + said(messages.get(i)), reply));
      }
    }
    return turns;
  }

  private static String said(JsonNode message) {
    return message.path("speaker").asString("") + ": " + message.path("text").asString("");
  }

  private static List<Question> questionsOf(JsonNode parsed) {
    List<Question> questions = new ArrayList<>();
    for (JsonNode qa : parsed.path("qa")) {
      int category = qa.path("category").asInt(0);
      if (category >= FIRST_CATEGORY
          && category <= LAST_CATEGORY
          && qa.path("evidence").size() > 0
          && qa.has("answer")) {
        questions.add(
            new Question(
                qa.path("question").asString(""), qa.path("answer").asString(""), category));
      }
    }
    return questions;
  }
}
