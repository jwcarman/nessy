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

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.notebook.Notebook;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * The package-private implementation of {@link ReflectionConfig}: the builder, carrying every
 * configured field and the {@link #build()} step that validates them and folds them into a real
 * {@code Consumer<ConversationSettled>} critic. {@link Reflection#critic(ReflectionCustomizer)} is
 * the only place one of these is constructed.
 */
final class ReflectionBuilder implements ReflectionConfig {

  static final String DEFAULT_PROMPT =
      "You are reviewing a settled agent conversation to extract durable lessons for next time."
          + " Read the transcript below and decide what this agent should remember: what worked,"
          + " what failed, and what it should do differently next time a similar situation comes"
          + " up. Respond with ONLY a JSON array of zero or more lessons — no prose before or"
          + " after it. Each lesson is an object with two string fields: \"hook\" (a short"
          + " one-line summary for an index) and \"body\" (the full lesson). Respond with []"
          + " when there is nothing worth remembering.";

  private Transcript transcript;
  private Notebook notebook;
  private Function<ConversationId, SubjectId> subject;
  private ModelProvider provider;
  private String model;
  private String prompt = DEFAULT_PROMPT;
  private boolean reflectOnSuccess;

  ReflectionBuilder() {}

  @Override
  public ReflectionBuilder transcript(Transcript transcript) {
    this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
    return this;
  }

  @Override
  public ReflectionBuilder notebook(Notebook notebook) {
    this.notebook = Objects.requireNonNull(notebook, "notebook must not be null");
    return this;
  }

  @Override
  public ReflectionBuilder subject(Function<ConversationId, SubjectId> subject) {
    this.subject = Objects.requireNonNull(subject, "subject must not be null");
    return this;
  }

  @Override
  public ReflectionBuilder provider(ModelProvider provider) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    return this;
  }

  @Override
  public ReflectionBuilder model(String model) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    this.model = model;
    return this;
  }

  @Override
  public ReflectionBuilder prompt(String prompt) {
    this.prompt = Objects.requireNonNull(prompt, "prompt must not be null");
    return this;
  }

  @Override
  public ReflectionBuilder reflectOnSuccess(boolean reflectOnSuccess) {
    this.reflectOnSuccess = reflectOnSuccess;
    return this;
  }

  /**
   * Turns this config into the critic it describes — the factory's own step, never a public {@code
   * build()} (design of record 2026-08-16 §1). Reached only from {@link
   * Reflection#critic(ReflectionCustomizer)}, once {@code customize} has returned.
   *
   * @throws IllegalStateException naming whichever required field is missing
   */
  Consumer<ConversationSettled> build() {
    validate();
    return new ReflectionCritic(
        transcript, notebook, subject, provider, model, prompt, reflectOnSuccess);
  }

  private void validate() {
    if (transcript == null) {
      throw new IllegalStateException("transcript is required: call .transcript(...)");
    }
    if (notebook == null) {
      throw new IllegalStateException("notebook is required: call .notebook(...)");
    }
    if (subject == null) {
      throw new IllegalStateException("subject is required: call .subject(...)");
    }
    if (provider == null) {
      throw new IllegalStateException("provider is required: call .provider(...)");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalStateException(
          "model is required: call .model(...) — reflection spends tokens, so there is no silent"
              + " default");
    }
  }
}
