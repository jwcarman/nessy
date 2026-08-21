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
package org.jwcarman.nessy.agent.intent;

import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.intent.Intent;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.intent.IntentStore;

/**
 * The claim channel's own tool (authorization design §7): the model declares what it is about to do
 * and why before it calls anything else, and this tool's only job is to record that claim into an
 * {@link IntentStore} so a later {@link IntentEnricher} can deposit it for a policy to read.
 *
 * <p>A named public class, not {@link Tool#of} — users reference {@code IntentTool} directly to
 * wire the same store into both this tool's grant and the {@link IntentEnricher} of the tool whose
 * call the declaration is meant to explain.
 */
public final class IntentTool implements Tool<IntentTool.DeclareIntent> {

  /** The tool's own input: the model's declaration of what it is about to do and why. */
  public record DeclareIntent(String intent) {}

  private final IntentStore store;

  public IntentTool(IntentStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  @Override
  public String name() {
    return "declare-intent";
  }

  @Override
  public String description() {
    return "Declare what you are about to do and why, before using any other tool.";
  }

  @Override
  public Class<DeclareIntent> inputType() {
    return DeclareIntent.class;
  }

  @Override
  public Awaited<ToolResult> execute(DeclareIntent input, ToolContext context) {
    store.record(new Intent(input.intent()));
    return Awaited.ready(ToolResult.ok("intent recorded"));
  }
}
