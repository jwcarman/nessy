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

import java.util.function.Function;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.notebook.Notebook;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * What {@link Reflection#critic(ReflectionCustomizer)} hands a customizer: a CONFIG, not a builder
 * (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}. An interface (owner
 * ruling, 2026-08-16 evening, same as {@link org.jwcarman.nessy.api.turn.TurnObserverConfig}): only
 * these verbs are reachable through this reference; the package-private implementation the factory
 * hands out typed as this interface carries the assembly into a real critic.
 *
 * <p>{@link #transcript}, {@link #notebook}, {@link #subject}, {@link #provider}, and {@link
 * #model} are required — {@link Reflection#critic(ReflectionCustomizer)} throws {@link
 * IllegalStateException} naming whichever is missing once {@code customize} returns. {@link #model}
 * carries no silent default: reflection is a token spend, so the model is always an explicit
 * choice. {@link #prompt} and {@link #reflectOnSuccess} are optional — a default critic prompt
 * ships, and {@code reflectOnSuccess} defaults to {@code false} (design of record §3: FAILED
 * settlements always reflect, COMPLETE only when opted in).
 */
public interface ReflectionConfig {

  /** The transcript the critic reads the settled conversation from. Required. */
  ReflectionConfig transcript(Transcript transcript);

  /** The notebook lessons are written to, through the trusted store handle. Required. */
  ReflectionConfig notebook(Notebook notebook);

  /**
   * The conversation-to-subject bridge. Required. A resolver that returns {@code null} for a given
   * conversation skips that conversation entirely — no model call, no lessons.
   */
  ReflectionConfig subject(Function<ConversationId, SubjectId> subject);

  /** The model provider the critic's side call is made against. Required. */
  ReflectionConfig provider(ModelProvider provider);

  /**
   * The model the critic's side call spends against. Required — no silent default spend.
   *
   * @throws IllegalArgumentException if {@code model} is blank
   */
  ReflectionConfig model(String model);

  /** The critic's system prompt. Optional — a default critic prompt ships. */
  ReflectionConfig prompt(String prompt);

  /**
   * Whether a {@code COMPLETE} settlement also reflects. Optional, defaults to {@code false}: a
   * {@code FAILED} settlement always reflects regardless of this setting.
   */
  ReflectionConfig reflectOnSuccess(boolean reflectOnSuccess);
}
