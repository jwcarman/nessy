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
 * How the {@link CodecPipeline} reaches a serializer Pekko builds for itself.
 *
 * <p>Pekko instantiates a serializer reflectively from {@code .conf}, so it cannot be handed
 * anything at construction — except an {@link ExtendedActorSystem}, which it IS given when it
 * declares that constructor. An extension hangs off that system, so the serializer can ask for the
 * pipeline the harness configured instead of inventing its own.
 *
 * <p>Without this the actor state would be stored raw while everything going through {@code
 * Substrate} was compressed or encrypted — the exact split the one-pipeline rule exists to prevent.
 *
 * <p><b>Set once, before any agent runs.</b> The factory installs the pipeline while building; the
 * serializer reads it per call, so a system that never had one keeps storing payloads untransformed
 * rather than failing.
 */
public final class EngineCodecs extends AbstractExtensionId<EngineCodecs.Pipelines>
    implements ExtensionIdProvider {

  /** The one id Pekko keys the extension by. */
  public static final EngineCodecs INSTANCE = new EngineCodecs();

  private EngineCodecs() {}

  @Override
  public ExtensionId<? extends Extension> lookup() {
    return INSTANCE;
  }

  @Override
  public Pipelines createExtension(ExtendedActorSystem system) {
    return new Pipelines();
  }

  /** The pipeline this actor system's serializers should use. */
  public static Pipelines of(ClassicActorSystemProvider system) {
    return INSTANCE.get(system);
  }

  /** The pipeline this actor system's serializers should use. */
  public static Pipelines of(ActorSystem system) {
    return INSTANCE.get(system);
  }

  /** Engine-scoped codec state, one per actor system. */
  public static final class Pipelines implements Extension {

    private final AtomicReference<CodecPipeline> pipeline =
        new AtomicReference<>(CodecPipeline.none());

    private Pipelines() {}

    /**
     * Installs the pipeline every serializer on this system will use.
     *
     * <p>Called by the harness factory before any agent starts. Changing it while payloads are
     * being written is not supported and not defended against: the chain is recorded in each
     * payload, so already-written bytes stay readable, but a half-switched system is nobody's
     * intent.
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
