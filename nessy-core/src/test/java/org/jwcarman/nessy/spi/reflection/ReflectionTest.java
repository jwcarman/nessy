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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.notebook.Notebook;
import org.jwcarman.nessy.spi.transcript.Transcript;
import org.slf4j.LoggerFactory;

class ReflectionTest {

  private final Transcript transcript = Transcript.inMemory();
  private final Notebook notebook = Notebook.inMemory();
  private final ScriptedCriticProvider provider = new ScriptedCriticProvider();
  private final SubjectId subject = new SubjectId("subject-1");

  private ConversationId told(String userText) {
    ConversationId id = ConversationId.generate();
    transcript.append(id, Message.user(userText));
    return id;
  }

  private Consumer<ConversationSettled> critic(ReflectionCustomizer extra) {
    return Reflection.critic(
        c -> {
          c.transcript(transcript)
              .notebook(notebook)
              .subject(conversationId -> subject)
              .provider(provider)
              .model("critic-model");
          extra.customize(c);
        });
  }

  private Consumer<ConversationSettled> defaultCritic() {
    return critic(c -> {});
  }

  @Nested
  class Triggering {

    @Test
    void a_failed_settlement_reflects_by_default() {
      ConversationId id = told("did the thing");
      provider.reply("[{\"hook\": \"h\", \"body\": \"b\"}]");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(provider.callCount()).isEqualTo(1);
      assertThat(notebook.find(subject, "lesson:" + id.value())).isPresent();
    }

    @Test
    void a_complete_settlement_is_skipped_unless_reflect_on_success_is_enabled() {
      ConversationId id = told("did the thing");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.COMPLETE, null, "done"));

      assertThat(provider.callCount()).isZero();
      assertThat(notebook.headings(subject)).isEmpty();
    }

    @Test
    void a_complete_settlement_reflects_when_reflect_on_success_is_enabled() {
      ConversationId id = told("did the thing");
      provider.reply("[{\"hook\": \"h\", \"body\": \"b\"}]");
      Consumer<ConversationSettled> reflect = critic(c -> c.reflectOnSuccess(true));

      reflect.accept(new ConversationSettled(id, ConversationStatus.COMPLETE, null, "done"));

      assertThat(provider.callCount()).isEqualTo(1);
      assertThat(notebook.find(subject, "lesson:" + id.value())).isPresent();
    }
  }

  @Nested
  class Subject_resolution {

    @Test
    void a_null_subject_skips_the_conversation_with_no_model_call() {
      ConversationId id = told("did the thing");
      Consumer<ConversationSettled> reflect =
          Reflection.critic(
              c ->
                  c.transcript(transcript)
                      .notebook(notebook)
                      .subject(conversationId -> null)
                      .provider(provider)
                      .model("critic-model"));

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(provider.callCount()).isZero();
      assertThat(notebook.headings(subject)).isEmpty();
    }
  }

  @Nested
  class Lesson_writing {

    @Test
    void a_replayed_settlement_overwrites_the_same_lesson_names_instead_of_duplicating() {
      ConversationId id = told("did the thing");
      provider.reply("[{\"hook\": \"h1\", \"body\": \"b1\"}]");
      provider.reply("[{\"hook\": \"h2\", \"body\": \"b2\"}]");
      Consumer<ConversationSettled> reflect = defaultCritic();
      ConversationSettled settled =
          new ConversationSettled(id, ConversationStatus.FAILED, "boom", "");

      reflect.accept(settled);
      reflect.accept(settled);

      assertThat(notebook.headings(subject)).hasSize(1);
      assertThat(notebook.find(subject, "lesson:" + id.value()))
          .isPresent()
          .get()
          .extracting(Notebook.Entry::body)
          .isEqualTo("b2");
    }

    @Test
    void multiple_lessons_get_deterministic_suffixed_names() {
      ConversationId id = told("did the thing");
      provider.reply(
          "[{\"hook\": \"h1\", \"body\": \"b1\"}, {\"hook\": \"h2\", \"body\": \"b2\"},"
              + " {\"hook\": \"h3\", \"body\": \"b3\"}]");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(notebook.headings(subject)).hasSize(3);
      assertThat(notebook.find(subject, "lesson:" + id.value())).isPresent();
      assertThat(notebook.find(subject, "lesson:" + id.value() + "-2")).isPresent();
      assertThat(notebook.find(subject, "lesson:" + id.value() + "-3")).isPresent();
    }

    @Test
    void every_lesson_is_sourced_from_reflection() {
      ConversationId id = told("did the thing");
      provider.reply("[{\"hook\": \"h\", \"body\": \"b\"}]");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(notebook.find(subject, "lesson:" + id.value()))
          .isPresent()
          .get()
          .extracting(Notebook.Entry::source)
          .isEqualTo("reflection");
    }
  }

  @Nested
  class Parsing {

    @Test
    void a_fenced_json_response_parses_leniently() {
      ConversationId id = told("did the thing");
      provider.reply("```json\n[{\"hook\": \"h\", \"body\": \"b\"}]\n```");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(notebook.find(subject, "lesson:" + id.value()))
          .isPresent()
          .get()
          .extracting(Notebook.Entry::hook)
          .isEqualTo("h");
    }

    @Test
    void a_bare_fenced_response_parses_leniently() {
      ConversationId id = told("did the thing");
      provider.reply("```\n[{\"hook\": \"h\", \"body\": \"b\"}]\n```");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(notebook.find(subject, "lesson:" + id.value())).isPresent();
    }
  }

  @Nested
  class Failure_handling {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void wires_a_capturing_appender_onto_the_critic_logger() {
      logger = (Logger) LoggerFactory.getLogger(ReflectionCritic.class);
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
    void an_unparseable_response_is_dropped_and_warns_naming_the_conversation() {
      ConversationId id = told("did the thing");
      provider.reply("sorry, I cannot comply with that request");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(notebook.headings(subject)).isEmpty();
      assertThat(warnings()).isNotEmpty();
      assertThat(warnings().getFirst().getFormattedMessage()).contains(id.value());
    }

    @Test
    void a_throwing_critic_provider_never_disturbs_the_settling_drive() {
      ConversationId id = told("did the thing");
      provider.throwing(new IllegalStateException("critic model unavailable"));
      Consumer<ConversationSettled> reflect = defaultCritic();
      ConversationSettled settled =
          new ConversationSettled(id, ConversationStatus.FAILED, "boom", "");

      reflect.accept(settled);

      assertThat(notebook.headings(subject)).isEmpty();
      assertThat(warnings()).isNotEmpty();
      assertThat(warnings().getFirst().getFormattedMessage()).contains(id.value());
      assertThat(warnings().getFirst().getThrowableProxy().getClassName())
          .isEqualTo(IllegalStateException.class.getName());
    }
  }

  @Nested
  class Configuration {

    @Test
    void the_factory_rejects_a_null_customizer() {
      assertThatThrownBy(() -> Reflection.critic(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_missing_transcript_is_named_by_build_validation() {
      assertThatIllegalStateException()
          .isThrownBy(
              () ->
                  Reflection.critic(
                      c ->
                          c.notebook(notebook)
                              .subject(conversationId -> subject)
                              .provider(provider)
                              .model("critic-model")))
          .withMessageContaining("transcript");
    }

    @Test
    void a_missing_notebook_is_named_by_build_validation() {
      assertThatIllegalStateException()
          .isThrownBy(
              () ->
                  Reflection.critic(
                      c ->
                          c.transcript(transcript)
                              .subject(conversationId -> subject)
                              .provider(provider)
                              .model("critic-model")))
          .withMessageContaining("notebook");
    }

    @Test
    void a_missing_subject_resolver_is_named_by_build_validation() {
      assertThatIllegalStateException()
          .isThrownBy(
              () ->
                  Reflection.critic(
                      c ->
                          c.transcript(transcript)
                              .notebook(notebook)
                              .provider(provider)
                              .model("critic-model")))
          .withMessageContaining("subject");
    }

    @Test
    void a_missing_provider_is_named_by_build_validation() {
      assertThatIllegalStateException()
          .isThrownBy(
              () ->
                  Reflection.critic(
                      c ->
                          c.transcript(transcript)
                              .notebook(notebook)
                              .subject(conversationId -> subject)
                              .model("critic-model")))
          .withMessageContaining("provider");
    }

    @Test
    void a_missing_model_is_named_by_build_validation() {
      assertThatIllegalStateException()
          .isThrownBy(
              () ->
                  Reflection.critic(
                      c ->
                          c.transcript(transcript)
                              .notebook(notebook)
                              .subject(conversationId -> subject)
                              .provider(provider)))
          .withMessageContaining("model");
    }

    @Test
    void a_blank_model_is_rejected_immediately_by_the_setter() {
      assertThatThrownBy(
              () ->
                  Reflection.critic(
                      c ->
                          c.transcript(transcript)
                              .notebook(notebook)
                              .subject(conversationId -> subject)
                              .provider(provider)
                              .model("   ")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
