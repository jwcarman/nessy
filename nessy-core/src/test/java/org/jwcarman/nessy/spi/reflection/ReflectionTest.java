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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
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
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelRequest;
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

    @Test
    void the_settled_conversations_own_id_is_what_the_resolver_is_asked_about() {
      ConversationId id = told("did the thing");
      provider.reply("[{\"hook\": \"h\", \"body\": \"b\"}]");
      List<ConversationId> resolved = new ArrayList<>();
      Consumer<ConversationSettled> reflect =
          Reflection.critic(
              c ->
                  c.transcript(transcript)
                      .notebook(notebook)
                      .subject(
                          conversationId -> {
                            resolved.add(conversationId);
                            return subject;
                          })
                      .provider(provider)
                      .model("critic-model"));

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(resolved).containsExactly(id);
    }
  }

  @Nested
  class Request_shape {

    @Test
    void the_critique_is_a_single_plain_text_user_message_with_no_tools_declared() {
      ConversationId id = told("attempt the flaky task");
      provider.reply("[]");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      ModelRequest request = provider.requests().getFirst();
      assertThat(request.tools()).isEmpty();
      List<Message> messages = request.context().messages();
      assertThat(messages).hasSize(1);
      Message only = messages.getFirst();
      assertThat(only.role()).isEqualTo(Role.USER);
      assertThat(only.content()).hasSize(1);
      assertThat(only.content().getFirst()).isInstanceOf(TextBlock.class);
    }

    @Test
    void a_tool_call_and_its_result_fold_into_one_line_instead_of_replaying_raw_blocks() {
      ConversationId id = ConversationId.generate();
      ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("x", 1);
      transcript.append(id, Message.user("add one and two"));
      transcript.append(
          id,
          Message.assistant(List.of(new ToolUseBlock(new ToolCall("call-1", "add", arguments)))));
      transcript.append(
          id, Message.toolResults(List.of(new ToolResultBlock("call-1", "3", false))));
      provider.reply("[]");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      ModelRequest request = provider.requests().getFirst();
      assertThat(request.context().messages()).hasSize(1);
      TextBlock rendered = (TextBlock) request.context().messages().getFirst().content().getFirst();
      assertThat(rendered.text())
          .contains("called add({\"x\":1}) → 3")
          .contains("User: add one and two");
    }

    @Test
    void the_configured_model_is_sent_on_the_critique_request() {
      ConversationId id = told("did the thing");
      provider.reply("[]");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(provider.requests().getFirst().model()).isEqualTo("critic-model");
    }

    @Test
    void a_custom_prompt_is_applied_as_the_critique_requests_system_prompt() {
      ConversationId id = told("did the thing");
      provider.reply("[]");
      Consumer<ConversationSettled> reflect = critic(c -> c.prompt("custom critic prompt"));

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(provider.requests().getFirst().systemPrompt()).isEqualTo("custom critic prompt");
    }

    @Test
    void a_null_failure_reason_omits_the_reason_line() {
      ConversationId id = told("did the thing");
      provider.reply("[]");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, null, ""));

      TextBlock rendered =
          (TextBlock)
              provider.requests().getFirst().context().messages().getFirst().content().getFirst();
      assertThat(rendered.text()).contains("This conversation FAILED.").doesNotContain("Reason:");
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

    @Test
    void a_throwing_subject_resolver_never_disturbs_the_settling_drive() {
      ConversationId id = told("did the thing");
      Consumer<ConversationSettled> reflect =
          Reflection.critic(
              c ->
                  c.transcript(transcript)
                      .notebook(notebook)
                      .subject(
                          conversationId -> {
                            throw new IllegalStateException("resolver unavailable");
                          })
                      .provider(provider)
                      .model("critic-model"));
      ConversationSettled settled =
          new ConversationSettled(id, ConversationStatus.FAILED, "boom", "");

      reflect.accept(settled);

      assertThat(provider.callCount()).isZero();
      assertThat(notebook.headings(subject)).isEmpty();
      assertThat(warnings()).isNotEmpty();
      assertThat(warnings().getFirst().getFormattedMessage()).contains(id.value());
    }

    @Test
    void valid_json_that_is_not_an_array_is_dropped_and_warns_naming_the_conversation() {
      ConversationId id = told("did the thing");
      provider.reply("{\"hook\": \"h\", \"body\": \"b\"}");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(notebook.headings(subject)).isEmpty();
      assertThat(warnings()).isNotEmpty();
      assertThat(warnings().getFirst().getFormattedMessage()).contains(id.value());
    }

    @Test
    void a_lesson_missing_its_hook_is_dropped_while_a_sibling_lesson_still_writes() {
      ConversationId id = told("did the thing");
      provider.reply("[{\"body\": \"missing a hook\"}, {\"hook\": \"h2\", \"body\": \"b2\"}]");
      Consumer<ConversationSettled> reflect = defaultCritic();

      reflect.accept(new ConversationSettled(id, ConversationStatus.FAILED, "boom", ""));

      assertThat(notebook.headings(subject)).hasSize(1);
      assertThat(notebook.find(subject, "lesson:" + id.value()))
          .isPresent()
          .get()
          .extracting(Notebook.Entry::body)
          .isEqualTo("b2");
      assertThat(warnings()).isNotEmpty();
      assertThat(warnings().getFirst().getFormattedMessage()).contains(id.value());
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
