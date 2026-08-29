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
package org.jwcarman.nessy.engine;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pekko.actor.AbstractExtensionId;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.ClassicActorSystemProvider;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.actor.Extension;
import org.apache.pekko.actor.ExtensionId;
import org.apache.pekko.actor.ExtensionIdProvider;
import org.jwcarman.nessy.api.agent.AgentType;

/**
 * Which agent types already have a locally-routed harness on this actor system.
 *
 * <p>Deliberately NOT called a claim: {@link Claims} is the claim-check store for tool arguments
 * and results, and one engine should not use that word for two unrelated things.
 *
 * <p>Only local routing needs this, and only because it makes the harness actor the parent: two
 * harnesses for one type would then parent rival {@code agent-<id>} children writing the same
 * persistence id — two {@code DurableStateBehavior}s fighting over one row. Sharding has no such
 * coupling and does not use this class.
 *
 * <p><b>Pekko will not catch it.</b> Asked to spawn a duplicate actor name, {@code SpawnProtocol}
 * silently RENAMES — measured 2026-08-29, {@code same} then {@code same-1} — so the corruption
 * would arrive with no error at all.
 *
 * <p><b>Why an extension.</b> Two factories on one system cannot see each other's fields, but both
 * can see the system. Pekko creates exactly one extension per system and synchronises that
 * creation, which makes {@link Taken#reserve} a real mutual exclusion rather than a hopeful one.
 *
 * <p><b>Per system is the whole guarantee here, not a weaker one.</b> A local actor system IS one
 * process; that is what "local" means. There is no second JVM for a claim to miss, so nothing is
 * being traded away — the only way to defeat it is to run two processes while configuring them
 * local, which is a deployment declaring a topology it does not have.
 */
public final class LocalAgentTypes extends AbstractExtensionId<LocalAgentTypes.Taken>
    implements ExtensionIdProvider {

  /** The one id Pekko keys this extension by. */
  public static final LocalAgentTypes INSTANCE = new LocalAgentTypes();

  private LocalAgentTypes() {}

  @Override
  public ExtensionId<? extends Extension> lookup() {
    return INSTANCE;
  }

  @Override
  public Taken createExtension(ExtendedActorSystem system) {
    return new Taken();
  }

  /** The agent types taken on this actor system. */
  public static Taken of(ClassicActorSystemProvider system) {
    return INSTANCE.get(system);
  }

  /** The agent types taken on this actor system. */
  public static Taken of(ActorSystem system) {
    return INSTANCE.get(system);
  }

  /** The taken types, one set per actor system. */
  public static final class Taken implements Extension {

    private final Set<String> types = ConcurrentHashMap.newKeySet();

    private Taken() {}

    /**
     * Takes {@code agentType} for a locally-routed harness.
     *
     * @throws IllegalStateException if a harness on this system already holds it
     */
    public void reserve(AgentType agentType) {
      Objects.requireNonNull(agentType, "agentType must not be null");
      if (!types.add(agentType.name())) {
        throw new IllegalStateException(
            ("a harness for agent type '%s' already exists on this actor system. Local routing"
                    + " parents agents under the harness, so a second one would create rival"
                    + " agent-<id> children sharing a persistence id — two DurableStateBehaviors"
                    + " writing the same row. Pekko will not catch this; SpawnProtocol silently"
                    + " renames a duplicate.")
                .formatted(agentType.name()));
      }
    }

    /** Gives the type back, so a stopped harness's type can be built again. */
    public void release(AgentType agentType) {
      types.remove(Objects.requireNonNull(agentType, "agentType must not be null").name());
    }
  }
}
