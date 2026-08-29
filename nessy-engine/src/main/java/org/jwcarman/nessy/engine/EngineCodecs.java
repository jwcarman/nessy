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
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pekko.actor.AbstractExtensionId;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.ClassicActorSystemProvider;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.actor.Extension;
import org.apache.pekko.actor.ExtensionId;
import org.apache.pekko.actor.ExtensionIdProvider;
import org.jwcarman.nessy.spi.codec.CodecPipeline;

/**
 * Engine state that belongs to an {@code ActorSystem} rather than to any one harness.
 *
 * <p>Pekko creates exactly one extension instance per system and synchronises that creation, which
 * is the only reason this can enforce anything: a per-factory field cannot see a second factory on
 * the same system, and an extension can.
 *
 * <p>It holds the codec pipeline, because Pekko instantiates a serializer reflectively from {@code
 * .conf} and can hand it nothing except an {@link ExtendedActorSystem}. Without this, actor state
 * would be stored raw while everything through {@code Substrate} was compressed or encrypted.
 *
 * <p><b>The set of agent types already claimed by a LOCALLY-ROUTED harness.</b>
 *
 * <p>The invariant that actually matters is <b>deterministic routing to exactly one agent actor per
 * (type, id)</b> — how many harness actors exist is irrelevant to it. This claim is not a rule
 * about harnesses; it is a workaround for a limitation of local parenting, where the harness actor
 * IS the parent, so two harnesses mean two {@code agent-<id>} children sharing a persistence id —
 * two {@code DurableStateBehavior}s writing the same row and fighting over revisions.
 *
 * <p>Pekko will not catch that for us. Asked to spawn a duplicate name, {@code SpawnProtocol}
 * silently RENAMES (measured 2026-08-29: {@code same}, then {@code same-1}), so the corruption
 * would arrive with no error at all.
 *
 * <p><b>Cluster sharding does not need this and must not use it.</b> Sharding routes every harness
 * to the same entity for a given (type key, id), so duplicate harnesses are harmless there — and it
 * enforces the real invariant cluster-wide, which nothing local can do across processes. When the
 * sharded strategy lands, it skips the claim rather than inheriting a rule that stopped applying.
 */
public final class EngineCodecs extends AbstractExtensionId<EngineCodecs.EngineState>
    implements ExtensionIdProvider {

  /** The one id Pekko keys the extension by. */
  public static final EngineCodecs INSTANCE = new EngineCodecs();

  private EngineCodecs() {}

  @Override
  public ExtensionId<? extends Extension> lookup() {
    return INSTANCE;
  }

  @Override
  public EngineState createExtension(ExtendedActorSystem system) {
    return new EngineState();
  }

  /** This actor system's engine state. */
  public static EngineState of(ClassicActorSystemProvider system) {
    return INSTANCE.get(system);
  }

  /** This actor system's engine state. */
  public static EngineState of(ActorSystem system) {
    return INSTANCE.get(system);
  }

  /** Engine state, one per actor system. */
  public static final class EngineState implements Extension {

    private final AtomicReference<CodecPipeline> pipeline =
        new AtomicReference<>(CodecPipeline.none());

    private EngineState() {}

    /**
     * Installs the pipeline every serializer on this system will use.
     *
     * <p>Called by the harness factory before any agent starts. The serializer reads it per call,
     * so install order does not matter as long as it happens before the first serialize.
     */
    public void use(CodecPipeline pipeline) {
      this.pipeline.set(Objects.requireNonNull(pipeline, "pipeline must not be null"));
    }

    /** The installed pipeline, or one that transforms nothing if none was installed. */
    public CodecPipeline pipeline() {
      return pipeline.get();
    }
  }
}
