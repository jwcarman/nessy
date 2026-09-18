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
package org.jwcarman.nessy.approval.intent;

import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The tool a model calls to say what it is about to do.
 *
 * <p>Its input type IS the vocabulary: a free-form {@link Intent}, or a sealed interface of the
 * shapes an application permits. The schema the model is shown comes from the engine's generator
 * over that type, so there is nothing to describe here that the type does not already say.
 */
public final class IntentTool<T> implements Tool<T> {

  private static final String FREEFORM_DESCRIPTION =
      "Declare what you are about to do and why, before using any other tool.";
  private static final String VOCABULARY_DESCRIPTION =
      "Declare what you are about to do, using one of the defined intent shapes, before using any"
          + " other tool.";

  private final Class<T> vocabulary;
  private final IntentStore<T> store;

  public IntentTool(Class<T> vocabulary, IntentStore<T> store) {
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  public static IntentTool<Intent> freeform(IntentStore<Intent> store) {
    return new IntentTool<>(Intent.class, store);
  }

  @Override
  public ToolName name() {
    return new ToolName("declare-intent");
  }

  @Override
  public String description() {
    return vocabulary == Intent.class ? FREEFORM_DESCRIPTION : VOCABULARY_DESCRIPTION;
  }

  @Override
  public Class<T> inputType() {
    return vocabulary;
  }

  /** Recording a claim is local work, so this can only ever come back ready. */
  @Override
  public Awaited<ToolResult> call(ToolCallRequest<T> request) {
    store.declare(request.agentId(), request.input());
    return Awaited.ready(ToolResult.ok(new Block.Text("intent recorded")));
  }
}
