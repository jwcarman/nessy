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
package org.jwcarman.nessy.spi;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/** Fixtures shared by {@link InProcessEngineTest} and {@link InProcessEngineObservationTest}. */
final class EngineFixtures {

  private EngineFixtures() {}

  record Echo(String value) {}

  /** A model that replays scripted turns, one per call, and tracks how its streams are held. */
  static final class FakeProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();
    int closedCount;
    int openStreams;
    int maxOpenStreams;

    // Takes a List of turns rather than varargs: generic varargs would raise an
    // unchecked warning, and this project forbids @SuppressWarnings outright.
    FakeProvider(List<List<ModelEvent>> scripted) {
      turns.addAll(scripted);
    }

    /** Every request this provider was handed, oldest first. */
    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      requests.add(request);
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      openStreams++;
      maxOpenStreams = Math.max(maxOpenStreams, openStreams);
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          openStreams--;
          closedCount++;
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  static final class EchoTool implements Tool<Echo> {

    private final boolean needsApproval;

    EchoTool(boolean needsApproval) {
      this.needsApproval = needsApproval;
    }

    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "Echoes its input";
    }

    @Override
    public Class<Echo> inputType() {
      return Echo.class;
    }

    /**
     * Test-fixture-only signal, not part of {@link Tool}: which {@link UsagePolicy} {@link
     * #defaultGrants} should attach to this instance, so call sites can keep writing {@code new
     * EchoTool(true)} / {@code new EchoTool(false)} for the two authority scenarios engine tests
     * exercise without hand-rolling a grant at every call site.
     */
    boolean needsApproval() {
      return needsApproval;
    }

    @Override
    public Awaited<ToolResult> execute(Echo input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("echoed:" + input.value()));
    }
  }

  static ObjectNode echoArgs(String value) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("value", value);
    return args;
  }

  /**
   * A grant per registered tool, every one explicitly policed: an {@link EchoTool} gets {@link
   * UsagePolicy#requireApproval()} or {@link UsagePolicy#allow()} depending on {@link
   * EchoTool#needsApproval()}; anything else gets {@link UsagePolicy#allow()}. Lets engine tests
   * that only care about execution, or about {@code EchoTool}'s own two-scenario authority story,
   * keep passing a bare {@link ToolRegistry} without hand-rolling a grant map at every call site.
   */
  static Map<String, ToolGrant> defaultGrants(ToolRegistry tools) {
    Map<String, ToolGrant> grants = new LinkedHashMap<>();
    for (ToolSpec spec : tools.specs()) {
      tools
          .find(spec.name())
          .ifPresent(
              tool -> {
                UsagePolicy policy =
                    tool instanceof EchoTool echo && echo.needsApproval()
                        ? UsagePolicy.requireApproval()
                        : UsagePolicy.allow();
                grants.put(spec.name(), ToolGrant.grant(tool, policy));
              });
    }
    return grants;
  }

  /** A plain, no-recall/no-shape {@link ContextPipeline} for tests that don't exercise it. */
  static ContextPipeline contextPipeline() {
    return ContextPipeline.builder().build(EventEmitter.noop(), ObservationRegistry.NOOP);
  }
}
