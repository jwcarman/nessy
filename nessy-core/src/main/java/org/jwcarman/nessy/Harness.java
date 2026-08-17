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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.internal.subagent.CallbackRouter;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.intent.IntentStore;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;

/**
 * The application's infrastructure, assembled once and shared by every agent it builds — inert
 * wiring, nothing more.
 *
 * <p>Nessy's front door is a two-config story, disjoint by design (design §17's razor). A {@code
 * Harness} owns the substrate — the model provider, session store, observation registry, and object
 * mapper — that make sense once per application, not once per agent; none of it is overridable from
 * {@link AgentConfig}, which owns identity instead. {@link #defaultModel()} and this harness's
 * {@link org.jwcarman.nessy.api.event.ListenerRegistry} are <em>seeded</em> rather than owned
 * outright: an agent may supply its own model, and always gets its own registrations appended after
 * the harness's, via {@link org.jwcarman.nessy.api.event.ListenerRegistry#extendedWith}.
 *
 * <p>{@link #agent(AgentCustomizer)} and {@link #agent(Class, AgentCustomizer)} hand the customizer
 * an {@link AgentConfig} pre-wired with this harness's shared pieces, ready to be given the
 * identity — model, system prompt, tools, policies — that makes it a particular agent, and return
 * the finished {@link Agent}. The odd-one-out agent (a different provider, a different store) is a
 * second harness, never an override on this one.
 *
 * <p><strong>{@code Harness} is immutable after construction: every field here is final, and no
 * method on this class ever writes to one.</strong> It is a front door for <em>building</em> agents
 * only. Callbacks — {@code resume}, {@code approve}, {@code deny}, {@code progress}, {@code peek} —
 * do not live here; they live on {@link Agent}, the identity that actually owns the loop, the
 * grants, and the registry a callback needs to act (design of record amendment, 2026-08-14: "the
 * callbacks should not be coming to the harness. They should always go through the agent"). A
 * harness that built ten agents has ten front doors for callbacks, not one, and this class holds
 * none of them.
 */
public final class Harness {

  private final ModelProvider provider;
  private final ConversationStore store;
  private final boolean storeSet;
  private final Parks parks;
  private final SubagentLinks subagentLinks;
  private final boolean subagentLinksSet;
  private final IntentStore intentStore;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;
  private final String defaultModel;
  private final ListenerRegistry registry;

  /**
   * Every agent and subagent this harness has ever built, registered the moment {@link
   * AgentAssembly#build(AgentConfig)} finishes assembling it (subagents strictly before the parent
   * whose build triggered them) — {@link #subagents()}'s own duplicate-name rejection (design of
   * record 2026-08-16 §2) is what turns a name collision anywhere in the harness's whole delegation
   * tree into an {@link IllegalArgumentException} at build time. Also the delivery address {@link
   * org.jwcarman.nessy.internal.subagent.AgentTools#completions}'s wake-up listener resolves a
   * settled child's parent against, by the name {@link
   * org.jwcarman.nessy.spi.conversation.Parks.Park#agentName()} carries — the v1 {@code
   * CallbackRouter} role, now internal to this harness rather than an application-assembled piece.
   * One fresh instance per harness, never configurable — there is nothing an application could
   * usefully seed it with ahead of the agents that populate it.
   */
  private final CallbackRouter subagentRegistry = new CallbackRouter();

  /**
   * The session store and whether {@link HarnessConfig#store(ConversationStore)} was ever called to
   * choose it explicitly — bundled together (java:S107: an eighth constructor parameter otherwise)
   * because they are never meaningful apart: {@link #storeSet()} exists only to describe {@link
   * #store()}'s own provenance.
   */
  record StoreSelection(ConversationStore store, boolean storeSet) {}

  /**
   * The two coordination stores every subagent shares from the harness's store family (design of
   * record 2026-08-16 §3) — bundled together (java:S107) since neither is meaningful without the
   * other once subagent construction internalizes both.
   */
  record CoordinationStores(Parks parks, SubagentLinks subagentLinks, boolean subagentLinksSet) {

    CoordinationStores {
      Objects.requireNonNull(parks, "parks must not be null");
      Objects.requireNonNull(subagentLinks, "subagentLinks must not be null");
    }
  }

  /**
   * Every store this harness carries, bundled together (java:S107: the intent store pushed the
   * constructor past seven parameters) — {@link StoreSelection} and {@link CoordinationStores} were
   * already their own bundles for the same reason; this is the next fold, not a new principle,
   * grouping the harness's whole store family under one constructor parameter.
   */
  record Stores(
      StoreSelection storeSelection,
      CoordinationStores coordinationStores,
      IntentStore intentStore) {

    Stores {
      Objects.requireNonNull(storeSelection, "storeSelection must not be null");
      Objects.requireNonNull(coordinationStores, "coordinationStores must not be null");
      Objects.requireNonNull(intentStore, "intentStore must not be null");
    }
  }

  Harness(
      ModelProvider provider,
      Stores stores,
      ObservationRegistry observations,
      ObjectMapper mapper,
      String defaultModel,
      ListenerRegistry registry) {
    this.provider = provider;
    this.store = stores.storeSelection().store();
    this.storeSet = stores.storeSelection().storeSet();
    this.parks = stores.coordinationStores().parks();
    this.subagentLinks = stores.coordinationStores().subagentLinks();
    this.subagentLinksSet = stores.coordinationStores().subagentLinksSet();
    this.intentStore = stores.intentStore();
    this.observations = observations;
    this.mapper = mapper;
    this.defaultModel = defaultModel;
    this.registry = registry;
  }

  /**
   * Builds an {@link Agent} from a live {@link AgentConfig}, over the {@code String} vocabulary —
   * the degenerate, single-text-block case: {@code customizer} fills in a fresh {@link AgentConfig}
   * pre-wired with this harness's infrastructure and defaulted to {@link InputRenderer#text()},
   * then this factory validates the required fields (name, model) and constructs the finished
   * {@link Agent}. No public {@code build()} survives here.
   */
  public Agent<String> agent(AgentCustomizer<String> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    AgentConfig<String> config = new AgentConfig<>(this, String.class, InputRenderer.text());
    customizer.customize(config);
    return config.build();
  }

  /**
   * Builds an {@link Agent} from a live {@link AgentConfig} over an application-owned input
   * vocabulary {@code T} — typically a sealed interface of records: {@code customizer} fills in a
   * fresh {@link AgentConfig} defaulted to {@link InputRenderer#json(ObjectMapper)} over this
   * harness's own mapper (override with {@link AgentConfig#renderer(InputRenderer)}); passing
   * {@code inputType} up front is what lets the compiler unify {@code T} across {@code inputType},
   * {@code customizer}, and the returned {@code Agent<T>} — a renderer that disagrees with the
   * vocabulary cannot be written. Type agreement is compile-time here, not a runtime check.
   */
  public <T> Agent<T> agent(Class<T> inputType, AgentCustomizer<T> customizer) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    AgentConfig<T> config = new AgentConfig<>(this, inputType, InputRenderer.json(mapper));
    customizer.customize(config);
    return config.build();
  }

  ModelProvider provider() {
    return provider;
  }

  ConversationStore store() {
    return store;
  }

  /**
   * Whether {@link HarnessConfig#store(ConversationStore)} was ever called on the config that
   * produced this harness — the bit {@link AgentConfig#resolvedMemory()} reads to know a durable
   * store was explicitly chosen, so it can warn when an agent's memory was left on the in-memory
   * default anyway (the same set-vs-defaulted mismatch {@link HarnessConfig}'s own parks-defaulting
   * guard checks).
   */
  boolean storeSet() {
    return storeSet;
  }

  Parks parks() {
    return parks;
  }

  /**
   * The subagent links store every subagent this harness builds shares (design of record 2026-08-16
   * §3) — the correlation {@link org.jwcarman.nessy.internal.subagent.AgentTools} uses to remember
   * which parent {@link org.jwcarman.nessy.api.ParkToken} a child conversation answers.
   */
  SubagentLinks subagentLinks() {
    return subagentLinks;
  }

  /**
   * Whether {@link HarnessConfig#subagentLinks(SubagentLinks)} was ever called on the config that
   * produced this harness — the bit {@link SubagentAssembly#build()} reads to warn when an agent
   * declares subagents against a harness whose own {@link #store} was explicitly chosen but whose
   * {@link #subagentLinks} was left on the in-memory default: a real durability gap (a settled
   * child after a restart leaves its parent parked forever, silently), not merely the "this harness
   * has no durable state at all" case {@link HarnessConfig}'s own subagent-links default stays
   * quiet for.
   */
  boolean subagentLinksSet() {
    return subagentLinksSet;
  }

  /**
   * Where a declared intent lives (design §7, Task 3b) — the store {@code AgentConfig.intent(...)}
   * reads and writes through, seeded from {@link HarnessConfig#intentStore(IntentStore)} or
   * defaulted to {@link IntentStore#inMemory()}.
   */
  IntentStore intentStore() {
    return intentStore;
  }

  /**
   * The internal name registry: every agent and subagent this harness has built, and the door
   * {@link org.jwcarman.nessy.internal.subagent.AgentTools#completions}'s wake-up listener resumes
   * a settled child's parent through.
   */
  CallbackRouter subagents() {
    return subagentRegistry;
  }

  ObservationRegistry observations() {
    return observations;
  }

  ObjectMapper mapper() {
    return mapper;
  }

  String defaultModel() {
    return defaultModel;
  }

  ListenerRegistry registry() {
    return registry;
  }
}
