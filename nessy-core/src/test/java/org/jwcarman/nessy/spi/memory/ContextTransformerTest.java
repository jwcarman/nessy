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
package org.jwcarman.nessy.spi.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.slf4j.LoggerFactory;

class ContextTransformerTest {

  /** A stage that always throws, with a fixed, distinctive {@code toString} to assert on. */
  private static final class BoomStage implements ContextTransformer {

    @Override
    public Context transform(ConversationId id, Context context) {
      throw new IllegalStateException("boom");
    }

    @Override
    public String toString() {
      return "boom-stage";
    }
  }

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;
  private Level originalLevel;

  @BeforeEach
  void wires_a_capturing_appender_onto_the_optional_transformer_logger() {
    logger = (Logger) LoggerFactory.getLogger(OptionalTransformer.class);
    originalLevel = logger.getLevel();
    logger.setLevel(Level.WARN);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void unwires_the_appender_and_restores_the_loggers_level() {
    logger.detachAppender(appender);
    logger.setLevel(originalLevel);
  }

  private List<ILoggingEvent> warnings() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  @Test
  void optional_swallows_the_failure_and_returns_the_input_context() {
    ConversationId id = ConversationId.generate();
    Context input = Context.of(List.of(Message.user("hello")));
    ContextTransformer throwing =
        (conversationId, context) -> {
          throw new IllegalStateException("boom");
        };
    ContextTransformer optional = ContextTransformer.optional(throwing);

    Context result = optional.transform(id, input);

    assertThat(result).isSameAs(input);
  }

  @Test
  void optional_logs_exactly_one_warning() {
    ConversationId id = ConversationId.generate();
    Context input = Context.of(List.of(Message.user("hello")));
    ContextTransformer throwing = new BoomStage();
    ContextTransformer optional = ContextTransformer.optional(throwing);

    optional.transform(id, input);

    assertThat(warnings()).hasSize(1);
    ILoggingEvent event = warnings().getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getThrowableProxy().getClassName())
        .isEqualTo(IllegalStateException.class.getName());
    // Spec §2.3: the WARN line must carry both the delegate's toString and the conversation id,
    // so a skipped stage still leaves a traceable line, not a generic "something failed" notice.
    assertThat(event.getFormattedMessage()).contains("boom-stage").contains(id.toString());
  }

  @Test
  void optional_passes_success_through_untouched() {
    ConversationId id = ConversationId.generate();
    Context input = Context.of(List.of(Message.user("hello")));
    Context transformed = Context.of(List.of(Message.user("hello"), Message.user("appended")));
    ContextTransformer succeeding = (conversationId, context) -> transformed;
    ContextTransformer optional = ContextTransformer.optional(succeeding);

    Context result = optional.transform(id, input);

    assertThat(result).isSameAs(transformed);
    assertThat(warnings()).isEmpty();
  }

  @Test
  void optional_rejects_null_delegate() {
    assertThatThrownBy(() -> ContextTransformer.optional(null))
        .isInstanceOf(NullPointerException.class);
  }
}
