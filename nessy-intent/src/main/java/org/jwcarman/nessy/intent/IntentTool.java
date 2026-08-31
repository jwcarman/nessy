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
package org.jwcarman.nessy.intent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Schemas;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The claim channel's own tool (authorization design §7): the model declares what it is about to do
 * before it calls anything else, and this tool's only job is to declare that claim into an {@link
 * IntentStore} so a later {@link IntentEnricher} can deposit it for a policy to read.
 *
 * <p>Generic over the vocabulary itself (vocabulary amendment §3, "One generic kit carries both"):
 * {@link #inputType()} returns the vocabulary class as-is and {@link #inputSchema()} runs it
 * through {@link Schemas}, so a sealed vocabulary rides that {@code oneOf} schema and the tool
 * executor's Jackson binding with zero extra code. The freeform tier is the pre-built {@code T =
 * Intent} instance returned by {@link #freeform(IntentStore)}.
 *
 * <p>A named public class, not {@link Tool#of} — users reference {@code IntentTool} directly to
 * wire the same store into both this tool's grant and the {@link IntentEnricher} of the tool whose
 * call the declaration is meant to explain.
 *
 * @param <T> the declared-intent vocabulary this tool accepts
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

  /** The freeform tier: the model's declaration arrives as a plain {@link Intent}. */
  public static IntentTool<Intent> freeform(IntentStore<Intent> store) {
    return new IntentTool<>(Intent.class, store);
  }

  @Override
  public String name() {
    return "declare-intent";
  }

  @Override
  public String description() {
    return vocabulary == Intent.class ? FREEFORM_DESCRIPTION : VOCABULARY_DESCRIPTION;
  }

  @Override
  public Class<T> inputType() {
    return vocabulary;
  }

  @Override
  public ObjectNode inputSchema() {
    return Schemas.of(vocabulary);
  }

  @Override
  public Awaited<ToolResult> execute(T input, ReplyToken replyTo) {
    store.declare(input);
    return Awaited.ready(ToolResult.ok("intent recorded"));
  }
}
