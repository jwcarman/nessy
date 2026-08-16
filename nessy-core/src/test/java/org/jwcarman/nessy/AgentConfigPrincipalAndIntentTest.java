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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.EffectfulTool;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.intent.IntentStore;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@code AgentConfig.principal(Function)} and {@code AgentConfig.intent(Class)}'s own runtime
 * behavior (design of record 2026-08-16-authorization §6, §7, Task 3b): both feeders reach the
 * {@link AuthorizationContext} a rung-1+ policy sees, and both stay fail-closed on their own terms.
 */
class AgentConfigPrincipalAndIntentTest {

  record Nothing() {}

  record ActEffect() {}

  /** An effectful tool whose only job is to exist so a call can be gated and observed. */
  private static final class ActTool implements EffectfulTool<Nothing, ActEffect> {

    @Override
    public String name() {
      return "act";
    }

    @Override
    public String description() {
      return "Acts.";
    }

    @Override
    public Class<Nothing> inputType() {
      return Nothing.class;
    }

    @Override
    public ActEffect effect(Nothing input) {
      return new ActEffect();
    }

    @Override
    public Awaited<ToolResult> execute(Nothing input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("acted"));
    }
  }

  /** A rung-1+ policy that always allows, but remembers every context it was handed. */
  private static final class CapturingPolicy implements UsagePolicy<Object> {

    private final List<AuthorizationContext> seen = new ArrayList<>();

    @Override
    public PolicyDecision evaluate(AuthorizationContext context, Object effect) {
      seen.add(context);
      return new PolicyDecision.Allow();
    }

    List<AuthorizationContext> seen() {
      return List.copyOf(seen);
    }
  }

  /** A model that replays one scripted turn (text or a tool call) per call, in order. */
  private static final class ScriptedProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns;
    private final List<ModelRequest> requests = new ArrayList<>();

    ScriptedProvider(List<List<ModelEvent>> turns) {
      this.turns = new ArrayDeque<>(turns);
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      requests.add(request);
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // intentionally empty: this fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  /** Records every {@link TurnEvent.ToolCallDecided} gate verdict narrated during one segment. */
  private static final class DecisionObserver implements TurnObserver {

    private final List<Decision> decisions = new ArrayList<>();

    @Override
    public void on(TurnEvent event) {
      if (event instanceof TurnEvent.ToolCallDecided(var _, Decision decision)) {
        decisions.add(decision);
      }
    }

    List<Decision> decisions() {
      return List.copyOf(decisions);
    }
  }

  private static List<ModelEvent> textTurn(String text) {
    return List.of(
        new ModelEvent.TextChunk(text),
        new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
  }

  private static List<ModelEvent> toolTurn(ToolCall call) {
    return List.of(
        new ModelEvent.ToolUseEmitted(call),
        new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
  }

  @Nested
  class Principal_recovery {

    @Test
    void a_resolved_principal_is_recovered_by_its_own_type() {
      CapturingPolicy policy = new CapturingPolicy();
      ToolCall call = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider = new ScriptedProvider(List.of(toolTurn(call), textTurn("done")));

      Nessy.harness(h -> h.provider(provider))
          .agent(
              Nothing.class,
              a ->
                  a.name("scribe")
                      .model("fake-model")
                      .principal(id -> "ada")
                      .tools(ToolGrant.grant(new ActTool(), List.of(), policy)))
          .converse()
          .tell(new Nothing());

      assertThat(policy.seen()).hasSize(1);
      assertThat(policy.seen().getFirst().principal(String.class)).contains("ada");
    }

    @Test
    void a_resolved_principal_recovered_by_the_wrong_type_reads_as_a_typed_miss() {
      CapturingPolicy policy = new CapturingPolicy();
      ToolCall call = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider = new ScriptedProvider(List.of(toolTurn(call), textTurn("done")));

      Nessy.harness(h -> h.provider(provider))
          .agent(
              Nothing.class,
              a ->
                  a.name("scribe")
                      .model("fake-model")
                      .principal(id -> "ada")
                      .tools(ToolGrant.grant(new ActTool(), List.of(), policy)))
          .converse()
          .tell(new Nothing());

      assertThat(policy.seen().getFirst().principal(Integer.class)).isEmpty();
    }

    @Test
    void an_unwired_agent_never_populates_the_principal_slot() {
      CapturingPolicy policy = new CapturingPolicy();
      ToolCall call = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider = new ScriptedProvider(List.of(toolTurn(call), textTurn("done")));

      Nessy.harness(h -> h.provider(provider))
          .agent(
              Nothing.class,
              a ->
                  a.name("scribe")
                      .model("fake-model")
                      .tools(ToolGrant.grant(new ActTool(), List.of(), policy)))
          .converse()
          .tell(new Nothing());

      assertThat(policy.seen()).hasSize(1);
      assertThat(policy.seen().getFirst().principal()).isEmpty();
    }

    @Test
    void a_throwing_resolver_fails_that_calls_authorization_closed() {
      CapturingPolicy policy = new CapturingPolicy();
      ToolCall call = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider = new ScriptedProvider(List.of(toolTurn(call), textTurn("done")));
      DecisionObserver observer = new DecisionObserver();

      var agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  Nothing.class,
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .principal(
                              id -> {
                                throw new IllegalStateException("directory unreachable");
                              })
                          .tools(ToolGrant.grant(new ActTool(), List.of(), policy)));

      agent.converse().tell(new Nothing(), observer);

      assertThat(policy.seen()).isEmpty();
      assertThat(observer.decisions()).hasSize(1);
      assertThat(observer.decisions().getFirst()).isInstanceOf(Decision.Deny.class);
      Decision.Deny deny = (Decision.Deny) observer.decisions().getFirst();
      assertThat(deny.reason()).contains("enricher failed");
    }
  }

  record RefundIntent(String reason) {}

  /** Counts every {@link #get} call — the store-never-touched proof for an unwired agent. */
  private static final class CountingIntentStore implements IntentStore {

    private final IntentStore delegate = IntentStore.inMemory();
    private final AtomicInteger gets = new AtomicInteger();

    @Override
    public Optional<StoredIntent> get(ConversationId id) {
      gets.incrementAndGet();
      return delegate.get(id);
    }

    @Override
    public void put(ConversationId id, String type, String json) {
      delegate.put(id, type, json);
    }

    @Override
    public void clear(ConversationId id) {
      delegate.clear(id);
    }

    int getCount() {
      return gets.get();
    }
  }

  private static ObjectNode refundArgs(String reason) {
    return JsonNodeFactory.instance.objectNode().put("reason", reason);
  }

  @Nested
  class Intent_lifetime {

    @Test
    void a_declared_intent_is_seen_by_a_later_calls_policy() {
      CapturingPolicy policy = new CapturingPolicy();
      ToolCall declare = new ToolCall("c1", "declare_intent", refundArgs("damaged item"));
      ToolCall act = new ToolCall("c2", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider(List.of(toolTurn(declare), toolTurn(act), textTurn("done")));

      Nessy.harness(h -> h.provider(provider))
          .agent(
              Nothing.class,
              a ->
                  a.name("scribe")
                      .model("fake-model")
                      .intent(RefundIntent.class)
                      .tools(ToolGrant.grant(new ActTool(), List.of(), policy)))
          .converse()
          .tell(new Nothing());

      assertThat(policy.seen()).hasSize(1);
      assertThat(policy.seen().getFirst().declaredIntent(RefundIntent.class))
          .contains(new RefundIntent("damaged item"));
    }

    @Test
    void redeclaring_replaces_the_prior_declaration() {
      CapturingPolicy policy = new CapturingPolicy();
      ToolCall declareFirst = new ToolCall("c1", "declare_intent", refundArgs("damaged item"));
      ToolCall declareSecond = new ToolCall("c2", "declare_intent", refundArgs("changed mind"));
      ToolCall act = new ToolCall("c3", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider(
              List.of(
                  toolTurn(declareFirst),
                  toolTurn(declareSecond),
                  toolTurn(act),
                  textTurn("done")));

      Nessy.harness(h -> h.provider(provider))
          .agent(
              Nothing.class,
              a ->
                  a.name("scribe")
                      .model("fake-model")
                      .intent(RefundIntent.class)
                      .tools(ToolGrant.grant(new ActTool(), List.of(), policy)))
          .converse()
          .tell(new Nothing());

      assertThat(policy.seen()).hasSize(1);
      assertThat(policy.seen().getFirst().declaredIntent(RefundIntent.class))
          .contains(new RefundIntent("changed mind"));
    }

    @Test
    void clearing_removes_the_declaration() {
      CapturingPolicy policy = new CapturingPolicy();
      ToolCall declare = new ToolCall("c1", "declare_intent", refundArgs("damaged item"));
      ToolCall clear = new ToolCall("c2", "clear_intent", JsonNodeFactory.instance.objectNode());
      ToolCall act = new ToolCall("c3", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider(
              List.of(toolTurn(declare), toolTurn(clear), toolTurn(act), textTurn("done")));

      Nessy.harness(h -> h.provider(provider))
          .agent(
              Nothing.class,
              a ->
                  a.name("scribe")
                      .model("fake-model")
                      .intent(RefundIntent.class)
                      .tools(ToolGrant.grant(new ActTool(), List.of(), policy)))
          .converse()
          .tell(new Nothing());

      assertThat(policy.seen()).hasSize(1);
      assertThat(policy.seen().getFirst().declaredIntent()).isEmpty();
    }

    @Test
    void an_unwired_agent_offers_no_declare_or_clear_tools_and_never_touches_the_store() {
      CountingIntentStore store = new CountingIntentStore();
      CapturingPolicy policy = new CapturingPolicy();
      ToolCall act = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider = new ScriptedProvider(List.of(toolTurn(act), textTurn("done")));

      var agent =
          Nessy.harness(h -> h.provider(provider).intentStore(store))
              .agent(
                  Nothing.class,
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .tools(ToolGrant.grant(new ActTool(), List.of(), policy)));

      agent.converse().tell(new Nothing());

      List<String> offeredToolNames =
          provider.requests().getFirst().tools().stream().map(spec -> spec.name()).toList();
      assertThat(offeredToolNames).isNotEmpty().doesNotContain("declare_intent", "clear_intent");
      assertThat(policy.seen()).hasSize(1);
      assertThat(policy.seen().getFirst().declaredIntent()).isEmpty();
      assertThat(store.getCount()).isZero();
    }

    @Test
    void a_rung_zero_static_grant_on_a_wired_agent_never_touches_the_intent_store() {
      CountingIntentStore store = new CountingIntentStore();
      ToolCall staticCall = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider(List.of(toolTurn(staticCall), textTurn("done")));

      var agent =
          Nessy.harness(h -> h.provider(provider).intentStore(store))
              .agent(
                  Nothing.class,
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .intent(RefundIntent.class)
                          .tools(ToolGrant.grant(new ActTool(), List.of(), UsagePolicy.allow())));

      agent.converse().tell(new Nothing());

      assertThat(store.getCount()).isZero();
    }

    @Test
    void a_rung_one_grant_on_the_same_wired_agent_does_touch_the_intent_store() {
      CountingIntentStore store = new CountingIntentStore();
      CapturingPolicy dynamicPolicy = new CapturingPolicy();
      ToolCall dynamicCall = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider(List.of(toolTurn(dynamicCall), textTurn("done")));

      var agent =
          Nessy.harness(h -> h.provider(provider).intentStore(store))
              .agent(
                  Nothing.class,
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .intent(RefundIntent.class)
                          .tools(ToolGrant.grant(new ActTool(), List.of(), dynamicPolicy)));

      agent.converse().tell(new Nothing());

      assertThat(dynamicPolicy.seen()).hasSize(1);
      assertThat(store.getCount()).isEqualTo(1);
    }
  }

  record OtherVocabulary(String note) {}

  @Nested
  class Intent_fail_closed_reads {

    @Test
    void a_foreign_vocabulary_row_reads_as_absent_rather_than_throwing() {
      CapturingPolicy policy = new CapturingPolicy();
      IntentStore store = IntentStore.inMemory();
      ToolCall act = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider = new ScriptedProvider(List.of(toolTurn(act), textTurn("done")));

      var agent =
          Nessy.harness(h -> h.provider(provider).intentStore(store))
              .agent(
                  Nothing.class,
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .intent(RefundIntent.class)
                          .tools(ToolGrant.grant(new ActTool(), List.of(), policy)));
      var conversation = agent.converse();
      store.put(
          conversation.conversationId(),
          OtherVocabulary.class.getName(),
          "{\"note\":\"not a refund\"}");

      conversation.tell(new Nothing());

      assertThat(policy.seen()).hasSize(1);
      assertThat(policy.seen().getFirst().declaredIntent()).isEmpty();
    }

    @Test
    void a_row_whose_type_no_longer_resolves_reads_as_absent_rather_than_throwing() {
      CapturingPolicy policy = new CapturingPolicy();
      IntentStore store = IntentStore.inMemory();
      ToolCall act = new ToolCall("c1", "act", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider = new ScriptedProvider(List.of(toolTurn(act), textTurn("done")));

      var agent =
          Nessy.harness(h -> h.provider(provider).intentStore(store))
              .agent(
                  Nothing.class,
                  a ->
                      a.name("scribe")
                          .model("fake-model")
                          .intent(RefundIntent.class)
                          .tools(ToolGrant.grant(new ActTool(), List.of(), policy)));
      var conversation = agent.converse();
      store.put(
          conversation.conversationId(),
          "com.example.RenamedAwayIntent",
          "{\"reason\":\"damaged item\"}");

      conversation.tell(new Nothing());

      assertThat(policy.seen()).hasSize(1);
      assertThat(policy.seen().getFirst().declaredIntent()).isEmpty();
    }
  }
}
