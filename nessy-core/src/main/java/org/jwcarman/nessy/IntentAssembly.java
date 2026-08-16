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
package org.jwcarman.nessy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.spi.intent.IntentStore;

/**
 * The internal assembly {@link AgentConfig#intent(Class)} promises and never names: the {@code
 * declare_intent} tool, the {@code clear_intent} tool, and the reader enricher, all built from one
 * {@code Class<?>} vocabulary plus the harness's own {@link IntentStore} (design of record
 * 2026-08-16-authorization §7, Task 3b). Package-private — {@link AgentAssembly} is the only
 * caller, and nothing here is a second public noun the withdrawn {@code IntentSupport} would have
 * been.
 */
final class IntentAssembly {

  private static final String DECLARE_TOOL_NAME = "declare_intent";
  private static final String CLEAR_TOOL_NAME = "clear_intent";

  private IntentAssembly() {}

  /** {@code declare_intent} and {@code clear_intent}, granted {@link UsagePolicy#allow()}. */
  static List<ToolGrant> grants(IntentStore store, Class<?> intentType, ObjectMapper mapper) {
    return grantsCaptured(store, intentType, mapper);
  }

  /** The reader enricher: one keyed store fetch per evaluated call, never a transcript scan. */
  static Enricher<Object> reader(IntentStore store, Class<?> intentType, ObjectMapper mapper) {
    return new IntentReaderEnricher(store, intentType, mapper);
  }

  /** Captures {@code intentType}'s wildcard so {@code Tool<X>}'s generic surface type-checks. */
  private static <X> List<ToolGrant> grantsCaptured(
      IntentStore store, Class<X> intentType, ObjectMapper mapper) {
    Tool<X> declare = new DeclareIntentTool<>(store, intentType, mapper);
    Tool<ClearIntent> clear = new ClearIntentTool(store);
    return List.of(
        ToolGrant.grant(declare, UsagePolicy.allow()), ToolGrant.grant(clear, UsagePolicy.allow()));
  }

  /** The wire shape {@code clear_intent} takes: no input, by design (design §7). */
  record ClearIntent() {}

  /** The tool the model calls to declare its intent — its input type IS {@code intentType}. */
  private static final class DeclareIntentTool<X> implements Tool<X> {

    private final IntentStore store;
    private final Class<X> intentType;
    private final ObjectMapper mapper;

    private DeclareIntentTool(IntentStore store, Class<X> intentType, ObjectMapper mapper) {
      this.store = store;
      this.intentType = intentType;
      this.mapper = mapper;
    }

    @Override
    public String name() {
      return DECLARE_TOOL_NAME;
    }

    @Override
    public String description() {
      return "Declare what you intend to do and why, in the "
          + intentType.getSimpleName()
          + " shape, before the calls this authorizes. Declaring again replaces your prior"
          + " declaration for this conversation.";
    }

    @Override
    public Class<X> inputType() {
      return intentType;
    }

    @Override
    public Awaited<ToolResult> execute(X input, ToolContext context) {
      String json;
      try {
        json = mapper.writeValueAsString(input);
      } catch (JsonProcessingException e) {
        return Awaited.ready(
            ToolResult.error("could not record your declared intent: " + e.getMessage()));
      }
      store.put(context.conversationId(), intentType.getName(), json);
      return Awaited.ready(ToolResult.ok("Declared."));
    }
  }

  /** The tool the model calls to withdraw its declared intent. Never parks. */
  private static final class ClearIntentTool implements Tool<ClearIntent> {

    private final IntentStore store;

    private ClearIntentTool(IntentStore store) {
      this.store = store;
    }

    @Override
    public String name() {
      return CLEAR_TOOL_NAME;
    }

    @Override
    public String description() {
      return "Withdraw your previously declared intent for this conversation.";
    }

    @Override
    public Class<ClearIntent> inputType() {
      return ClearIntent.class;
    }

    @Override
    public Awaited<ToolResult> execute(ClearIntent input, ToolContext context) {
      store.clear(context.conversationId());
      return Awaited.ready(ToolResult.ok("Cleared."));
    }
  }

  /**
   * The internal enricher {@link AgentConfig#intent(Class)} wires onto every non-static grant: one
   * keyed {@link IntentStore#get} per evaluated call, depositing under {@link
   * AuthzContext#DECLARED_INTENT} only when the stored row's own type name matches this agent's
   * configured vocabulary exactly.
   *
   * <p>Fail-closed by construction, never by catching a cast failure: a foreign vocabulary (a row
   * some other agent — or an earlier build of this one, before a class rename — declared under a
   * different type name) and a row whose JSON no longer deserializes into {@code intentType} both
   * leave the context exactly as this enricher received it, reading as absent rather than throwing.
   * Only the store's own failure (an I/O error, say) propagates, which the chokepoint turns into a
   * {@code Deny} naming the enricher stage — the general {@link Enricher} contract, undisturbed.
   */
  private static final class IntentReaderEnricher implements Enricher<Object> {

    private final IntentStore store;
    private final Class<?> intentType;
    private final ObjectMapper mapper;

    private IntentReaderEnricher(IntentStore store, Class<?> intentType, ObjectMapper mapper) {
      this.store = store;
      this.intentType = intentType;
      this.mapper = mapper;
    }

    @Override
    public Optional<String> displayName() {
      return Optional.of("intent");
    }

    @Override
    public AuthzContext enrich(AuthzContext context, Object effect) {
      Optional<IntentStore.StoredIntent> stored = store.get(context.conversationId());
      if (stored.isEmpty()) {
        return context;
      }
      IntentStore.StoredIntent intent = stored.get();
      if (!intentType.getName().equals(intent.type())) {
        // A foreign vocabulary, or this agent's own vocabulary under a name that no longer
        // resolves after a rename: reads as absent, never a ClassCastException, never an allow.
        return context;
      }
      Object declared;
      try {
        declared = mapper.readValue(intent.json(), intentType);
      } catch (IOException e) {
        // Malformed for this type: also reads as absent rather than denying the whole call.
        return context;
      }
      return context.with(AuthzContext.DECLARED_INTENT, declared);
    }
  }
}
