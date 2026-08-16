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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;
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

  /**
   * The wiring-time belt {@link AgentConfig#intent(Class)} runs before ever accepting {@code
   * intentType}: a tool's parameters must render as a JSON object schema, so anything that renders
   * otherwise — a bare {@code String}, a primitive or its box, an enum, a collection, an array — is
   * refused here rather than discovered later as a schema a provider rejects at call time. Reuses
   * {@link Tool#spec()}'s own schema derivation (a throwaway probe tool, never registered or
   * called) rather than reaching into {@code internal.Schemas} directly — {@code Tool.java} is
   * already the one sanctioned api-to-internal crossing for exactly this derivation ({@code
   * ZoneBoundariesTest}).
   */
  static void requireObjectSchema(Class<?> intentType) {
    requireConcreteType(intentType);
    requireObjectSchemaCaptured(intentType);
  }

  /**
   * Rejects an abstract type — a sealed or unsealed interface, or an abstract class — before ever
   * asking victools to render one. Empirical finding (Task 3b): victools 4.38.0 renders a sealed
   * interface of records as a bare {@code {"type":"object"}} with no {@code oneOf} and no
   * properties, so the model gets an empty schema and Jackson cannot reconstruct the abstract type
   * without {@code @JsonTypeInfo} and subtype-resolver wiring nessy does not do. Accepting that
   * shape at wiring time and failing at call time is silent non-functionality; nessy fails loudly
   * here instead, where the developer is standing (design of record 2026-08-16-authorization §7,
   * amended: polymorphic vocabularies are a roadmap item, not a v1 promise).
   */
  private static void requireConcreteType(Class<?> intentType) {
    if (intentType.isInterface() || Modifier.isAbstract(intentType.getModifiers())) {
      throw new AgentConfigurationException(
          "intent vocabulary "
              + intentType.getName()
              + " is abstract; victools cannot render a polymorphic schema. Use a concrete record"
              + " with a discriminator field (for example `record Intent(Kind kind, String"
              + " orderId, String reason)`).");
    }
  }

  private static <X> void requireObjectSchemaCaptured(Class<X> intentType) {
    JsonNode type = new SchemaProbeTool<>(intentType).spec().inputSchema().get("type");
    boolean isObjectSchema = type != null && type.isTextual() && "object".equals(type.asText());
    if (!isObjectSchema) {
      throw new AgentConfigurationException(
          "intent("
              + intentType.getName()
              + ") is rejected: its JSON schema is not an OBJECT schema — a tool's parameters must"
              + " be an object, and a bare String, a primitive or its box, an enum, a collection, or"
              + " an array all render as something else. Wrap it in a record instead, e.g. `record"
              + " "
              + intentType.getSimpleName()
              + "Intent("
              + intentType.getSimpleName()
              + " value) {}`.");
    }
  }

  /** Exists only so {@link Tool#spec()} can derive {@code intentType}'s own schema. */
  private static final class SchemaProbeTool<X> implements Tool<X> {

    private final Class<X> intentType;

    private SchemaProbeTool(Class<X> intentType) {
      this.intentType = intentType;
    }

    @Override
    public String name() {
      return "probe";
    }

    @Override
    public String description() {
      return "";
    }

    @Override
    public Class<X> inputType() {
      return intentType;
    }

    @Override
    public Awaited<ToolResult> execute(X input, ToolContext context) {
      throw new UnsupportedOperationException("this probe tool is never actually run");
    }
  }

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
   * AuthorizationContext#DECLARED_INTENT} only when the stored row's own type name matches this
   * agent's configured vocabulary exactly.
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
    public AuthorizationContext enrich(AuthorizationContext context, Object effect) {
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
      return context.with(AuthorizationContext.DECLARED_INTENT, declared);
    }
  }
}
