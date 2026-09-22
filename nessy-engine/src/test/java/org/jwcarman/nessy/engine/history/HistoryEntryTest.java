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
package org.jwcarman.nessy.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an entry refuses to be, and what it looks like once written down.
 *
 * <p>The invariants here are all the same invariant seen from different sides: an entry that
 * renders to nothing, or that claims an obligation it can never discharge, cannot be deleted later.
 * It is re-sent on every turn for as long as the conversation lives.
 */
class HistoryEntryTest {

  private final Codec<HistoryEntry> codec =
      new JacksonCodecFactory(JsonMapper.builder().build()).create(HistoryEntry.class);

  private static Block.ToolCall call(String id) {
    return new Block.ToolCall(id, "lookup", "{}");
  }

  private String encoded(HistoryEntry entry) {
    return new String(codec.encode(entry), StandardCharsets.UTF_8);
  }

  /**
   * The invariant the whole design rests on. An entry saying a turn is outstanding while owing
   * nothing is a turn nothing will ever close -- the agent waits forever on work it never asked
   * for. An inference that asked for nothing is an answer, and there is an entry for that.
   */
  @Test
  void aRequestForActionsThatAsksForNothingIsRefused() {
    Seq seq = new Seq(2);
    TurnId turn = new TurnId(1);
    List<Block.ActionRequestContent> onlyProse = List.of(new Block.Commentary("thinking out loud"));
    assertThatThrownBy(() -> new HistoryEntry.InferenceRequestedActions(seq, turn, onlyProse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one call");
  }

  @Test
  void aRequestForActionsKeepsEverythingTheModelSaidInOrder() {
    HistoryEntry.InferenceRequestedActions entry =
        new HistoryEntry.InferenceRequestedActions(
            new Seq(2),
            new TurnId(1),
            List.of(new Block.Commentary("let me look"), call("c1"), call("c2")));

    assertThat(entry.blocks()).hasSize(3);
    assertThat(entry.calls())
        .extracting(Block.ToolCall::id)
        .containsExactly(new CallId("c1"), new CallId("c2"));
  }

  /**
   * The id is the only link back to the call. Without one the call is never discharged.
   *
   * <p>Refused by {@code CallId} rather than by the entry, which is the point of it being a type:
   * one rule, enforced everywhere one is constructed, instead of a check each entry has to
   * remember. The message names what it is, so a caller holding two strings knows which was wrong.
   */
  @Test
  void aResultWithoutACallIdIsRefused() {
    assertThatThrownBy(() -> new CallId(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("call id");
  }

  /**
   * The wire requires a result for every call, and an empty one is not a result -- it is a request
   * that renders to nothing and gets re-sent that way forever.
   */
  @Test
  void aSuccessWithNoContentIsRefused() {
    Seq seq = new Seq(3);
    TurnId turn = new TurnId(1);
    CallId call = new CallId("c1");
    List<Block.ToolResultContent> nothing = List.of();
    assertThatThrownBy(() -> new HistoryEntry.ToolSucceeded(seq, turn, call, nothing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one block");
  }

  /** The message is the entire content the model reads about what went wrong. */
  @Test
  void aFailureWithNothingToSayIsRefused() {
    Seq seq = new Seq(3);
    TurnId turn = new TurnId(1);
    CallId call = new CallId("c1");
    assertThatThrownBy(() -> new HistoryEntry.ToolFailed(seq, turn, call, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("message");
  }

  @Test
  void aDenialWithNoReasonIsRefused() {
    Seq seq = new Seq(3);
    TurnId turn = new TurnId(1);
    CallId call = new CallId("c1");
    assertThatThrownBy(() -> new HistoryEntry.ToolDenied(seq, turn, call, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
  }

  /**
   * Discriminators are a stored format: transcripts on disk name them, so changing one is a
   * migration and these assertions are what say so out loud.
   */
  @Test
  void everyNewEntryIsStoredUnderItsOwnDiscriminator() {
    assertThat(
            encoded(
                new HistoryEntry.InferenceRequestedActions(
                    new Seq(2), new TurnId(1), List.of(call("c1")))))
        .contains("\"type\":\"inference-requested-actions\"");
    assertThat(
            encoded(
                new HistoryEntry.ToolSucceeded(
                    new Seq(3),
                    new TurnId(1),
                    new CallId("c1"),
                    HistoryEntry.ToolSucceeded.text("ok"))))
        .contains("\"type\":\"tool-succeeded\"");
    assertThat(
            encoded(
                new HistoryEntry.ToolFailed(new Seq(3), new TurnId(1), new CallId("c1"), "nope")))
        .contains("\"type\":\"tool-failed\"");
    assertThat(
            encoded(
                new HistoryEntry.ToolDenied(
                    new Seq(3), new TurnId(1), new CallId("c1"), "not allowed")))
        .contains("\"type\":\"tool-denied\"");
  }

  @Test
  void everyNewEntryRoundTripsThroughStorageUnchanged() {
    List<HistoryEntry> entries =
        List.of(
            new HistoryEntry.InferenceRequestedActions(
                new Seq(2), new TurnId(1), List.of(new Block.Commentary("looking"), call("c1"))),
            new HistoryEntry.ToolSucceeded(
                new Seq(3), new TurnId(1), new CallId("c1"), HistoryEntry.ToolSucceeded.text("ok")),
            new HistoryEntry.ToolFailed(new Seq(4), new TurnId(1), new CallId("c1"), "nope"),
            new HistoryEntry.ToolDenied(
                new Seq(5), new TurnId(1), new CallId("c1"), "not allowed"));

    assertThat(entries)
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(codec.decode(codec.encode(entry))).isEqualTo(entry));
  }
}
