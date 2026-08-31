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
package org.jwcarman.nessy.examples.chatweb;

import java.time.Clock;
import java.time.Duration;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.engine.PekkoHarnessFactory;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.memory.notebook.SubstrateNotebook;
import org.jwcarman.nessy.memory.pipeline.MemoryPipeline;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What only this application can know: how it reaches a model, what its tools are, which of them
 * needs a person, and where that person is asked.
 *
 * <p>There is no actor system here, no cluster, no serializers, no substrate — the starter owns all
 * of that. What is left is the application.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfiguration {

  static final AgentType TYPE = AgentType.of("chat");

  /** How long a proposed email may sit waiting for a person before it is abandoned. */
  private static final Duration APPROVAL_TERM = Duration.ofHours(1);

  private static final String SYSTEM_PROMPT =
      """
      You are a helpful assistant in a chat window. Keep answers short unless asked for more.

      When the user tells you something worth keeping — a preference, a name, a standing fact — \
      remember it as a note. Your notes appear as an index in every conversation; read one in \
      full with the recall tool when it is relevant.

      When a question turns on today's date or on counting days, use the days_until tool rather \
      than working it out yourself.

      You can also send email, but every send is shown to a person for approval before it \
      happens. So say what you are about to send, send it, and expect the result to arrive \
      later — possibly much later. Never claim an email has been sent until the tool says so.""";

  @Bean
  public ModelProvider models(ChatProperties properties) {
    return OpenAiModelProvider.create(
        config -> config.apiKey(properties.getModelApiKey()).baseUrl(properties.getModelUrl()));
  }

  @Bean
  public SendEmailTool sendEmailTool() {
    return new SendEmailTool();
  }

  /** Where the agent keeps what it has been told worth keeping. */
  @Bean
  public Notebook notebook(Substrate substrate) {
    return new SubstrateNotebook(substrate, TYPE);
  }

  /**
   * The harness, declared here rather than taken from the starter because this application gates a
   * tool — and an approver is a decision about THIS application's policy, which the starter cannot
   * know.
   */
  @Bean
  public Harness<String> harness(
      PekkoHarnessFactory factory,
      ChatProperties properties,
      SendEmailTool email,
      Approver desk,
      Memory memory,
      Notebook notebook) {
    return factory.createHarness(
        String.class,
        config ->
            config
                .type(TYPE)
                .systemPrompt(SYSTEM_PROMPT)
                .model(ModelId.of(properties.getModelId()))
                .renderer(UserMessage::of)
                .memory(memory)
                .tool(new DaysUntilTool())
                .tool(NotebookTools.remember(notebook))
                .tool(NotebookTools.recall(notebook))
                .tool(NotebookTools.forget(notebook))
                .tool(
                    email,
                    binding ->
                        binding
                            .approver(desk)
                            .describer(
                                input ->
                                    "Send an email to %s, subject \"%s\""
                                        .formatted(input.to(), input.subject()))));
  }

  /**
   * The approver that puts a question to a person.
   *
   * <p>It does two things and only two: tell the desk where the answer should come back, and say
   * how long the question stands. It never decides — deciding is what the page is for.
   */
  @Bean
  public Approver desk(ApprovalDesk desk, Clock clock) {
    return (request, context) -> {
      desk.expecting(request, context.replyToken());
      return Awaited.deferred(clock.instant().plus(APPROVAL_TERM));
    };
  }

  /**
   * What the agent remembers, and what it is shown.
   *
   * <p>The transcript, plus one stage that puts the notebook's index in front of the model. The
   * index is background — an {@code AmbientMessage} — so it is rebuilt on every call and never
   * written to the transcript: the model always sees the notes as they stand now, and the record
   * stays a record of what happened.
   *
   * <p>Eternal on purpose: a browser chat is short, and losing the start of it would be more
   * surprising than a long context. A long-lived agent wants {@code TranscriptMemory.recent}.
   */
  @Bean
  public Memory memory(Substrate substrate, Notebook notebook) {
    return MemoryPipeline.of(
        TranscriptMemory.eternal(substrate, TYPE),
        pipeline -> pipeline.stage(NotebookTools.index(notebook)));
  }
}
