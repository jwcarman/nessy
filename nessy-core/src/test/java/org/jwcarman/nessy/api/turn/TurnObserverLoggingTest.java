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
package org.jwcarman.nessy.api.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.slf4j.LoggerFactory;

/**
 * {@link TurnObserver#logging} against a real slf4j {@code Logger} — the first narration test
 * needing a log-capture technique in this codebase: a {@link ListAppender} wired onto a dedicated
 * logback {@code Logger} whose level is forced to {@code TRACE} (the lowest non-deprecated level on
 * the pinned logback-classic 1.6.1 — {@code ALL} is deprecated there), independent of {@code
 * logback-test.xml}'s package-wide {@code WARN} threshold.
 */
class TurnObserverLoggingTest {

  private static final String PREFIX = "watchman";
  private static final ToolCall CALL =
      new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
  private static final ParkToken TOKEN = ParkToken.generate();

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void wires_a_capturing_appender_onto_a_fresh_logger() {
    Logger classicLogger =
        (Logger) LoggerFactory.getLogger("TurnObserverLoggingTest." + System.nanoTime());
    classicLogger.setLevel(Level.TRACE);
    classicLogger.setAdditive(false);
    appender = new ListAppender<>();
    appender.start();
    classicLogger.addAppender(appender);
    logger = classicLogger;
  }

  @Test
  void assistant_said_logs_a_says_line_with_the_joined_text_blocks() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(
        new TurnEvent.AssistantSaid(
            Message.assistant(List.of(new TextBlock("Four"), new TextBlock(".")))));

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage()).isEqualTo("watchman says: Four.");
  }

  @Test
  void assistant_said_with_no_prose_logs_nothing() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(new TurnEvent.AssistantSaid(Message.assistant(List.of(new ToolUseBlock(CALL)))));

    assertThat(appender.list).isEmpty();
  }

  @Test
  void assistant_said_with_only_blank_text_logs_nothing() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(new TurnEvent.AssistantSaid(Message.assistant(List.of(new TextBlock("   ")))));

    assertThat(appender.list).isEmpty();
  }

  @Test
  void a_tool_request_logs_a_tool_line() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(new TurnEvent.ToolCallRequested(CALL));

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo("watchman tool: search");
  }

  @Test
  void a_tool_completion_logs_a_completed_line_carrying_the_error_flag() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(new TurnEvent.ToolCallCompleted(CALL, ToolResult.ok("done")));

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.getFirst().getFormattedMessage())
        .isEqualTo("watchman tool completed: search (error=false)");
  }

  @Test
  void a_park_logs_a_parked_line_carrying_the_token() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(new TurnEvent.ToolCallParked(CALL, TOKEN));

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.getFirst().getFormattedMessage())
        .isEqualTo("watchman parked: tool=search token=" + TOKEN.value());
  }

  @Test
  void turn_ended_logs_an_info_ends_line() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage()).isEqualTo("watchman ends: COMPLETE");
  }

  @Test
  void a_failed_turn_ended_also_logs_a_warn_carrying_the_failure_reason() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(new TurnEvent.TurnEnded(ConversationStatus.FAILED, "boom"));

    assertThat(appender.list).hasSize(2);
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo("watchman ends: FAILED");
    assertThat(appender.list.get(1).getLevel()).isEqualTo(Level.WARN);
    assertThat(appender.list.get(1).getFormattedMessage()).isEqualTo("watchman failed: boom");
  }

  @Test
  void a_non_failed_turn_ended_logs_no_warn() {
    TurnObserver observer = TurnObserver.logging(logger, PREFIX);

    observer.on(new TurnEvent.TurnEnded(ConversationStatus.PARKED, null));

    assertThat(appender.list).isNotEmpty().noneMatch(event -> event.getLevel() == Level.WARN);
  }

  @Test
  void a_null_logger_is_rejected() {
    assertThatThrownBy(() -> TurnObserver.logging(null, PREFIX))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_null_prefix_is_rejected() {
    String nullPrefix = null;

    assertThatThrownBy(() -> TurnObserver.logging(logger, nullPrefix))
        .isInstanceOf(NullPointerException.class);
  }

  @Nested
  class Deferred_prefix_supplier {

    @Test
    void each_line_carries_whatever_the_supplier_returns_at_its_own_narration() {
      List<String> prefixes = new ArrayList<>(List.of("unknown"));
      TurnObserver observer = TurnObserver.logging(logger, () -> prefixes.getFirst());

      observer.on(new TurnEvent.ToolCallRequested(CALL));
      prefixes.set(0, "order-42");
      observer.on(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));

      assertThat(appender.list).hasSize(2);
      assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo("unknown tool: search");
      assertThat(appender.list.get(1).getFormattedMessage()).isEqualTo("order-42 ends: COMPLETE");
    }

    @Test
    void a_constant_supplier_behaves_exactly_like_the_fixed_prefix_overload() {
      TurnObserver observer = TurnObserver.logging(logger, () -> PREFIX);

      observer.on(new TurnEvent.ToolCallRequested(CALL));

      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo("watchman tool: search");
    }

    @Test
    void a_null_logger_is_rejected() {
      assertThatThrownBy(() -> TurnObserver.logging(null, () -> PREFIX))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_null_prefix_supplier_is_rejected() {
      Supplier<String> nullSupplier = null;

      assertThatThrownBy(() -> TurnObserver.logging(logger, nullSupplier))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
