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
package org.jwcarman.nessy.api.memory;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.HistoryMessage;

/**
 * What an agent remembers of its own conversation.
 *
 * <p><b>One method per thing that can actually happen.</b> There is no method taking an arbitrary
 * sequence of messages, and none taking a lone {@link ToolResultMessage} — so a stray tool result,
 * a scrambled group, and a half-written exchange are not things a caller can express, rather than
 * things an implementation has to refuse. The three methods below ARE the three legal shapes.
 */
public interface Memory {

  /**
   * Everything this agent has been told and has said, as a context a provider will accept.
   *
   * <p>Always valid, because nothing invalid can be remembered: an assistant turn that called tools
   * is only ever written together with the message answering it. Implementations do not need to
   * withhold a dangling exchange on the way out — there is never one to hide.
   */
  Context recall(AgentId agentId);

  /**
   * Records something that happened.
   *
   * <p>One method, because {@link HistoryMessage} already says which messages may be remembered.
   * Background is not one of them, so it has no way in — rather than a rule somewhere saying it
   * must not.
   *
   * <p><b>The trade an exchange makes.</b> It is not durable until its tools finish, so a crash in
   * that window loses it and the model is called again. Deliberate: a repeated model call is
   * cheaper than a transcript persisted in a state nothing can read.
   */
  void remember(AgentId agentId, HistoryMessage message);

  /**
   * A memory that recalls through stages.
   *
   * <pre>{@code
   * Memory.pipeline(
   *     new Transcripts(substrate, TYPE),
   *     pipeline -> pipeline.stage(NotebookTools.index(notebook)));
   * }</pre>
   *
   * <p>{@code bootstrap} answers what the model should see before anything is added; the stages
   * shape it on the way out. Remembering goes straight through, so the record and the view can
   * never disagree.
   *
   * <p>A bootstrap that summarizes, snapshots, or reads from somewhere else entirely is an ordinary
   * {@code Memory} — it answers the same question differently, which is what implementations are
   * for.
   */
  static Memory pipeline(Memory bootstrap, MemoryPipelineCustomizer customizer) {
    java.util.Objects.requireNonNull(bootstrap, "bootstrap must not be null");
    java.util.Objects.requireNonNull(customizer, "customizer must not be null");
    MemoryPipelineConfig config = new MemoryPipelineConfig();
    customizer.customize(config);
    return new PipelineMemory(bootstrap, config.stages());
  }
}
