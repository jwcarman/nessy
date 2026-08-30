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

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Adapter;
import org.apache.pekko.serialization.SerializerWithStringManifest;

/**
 * What an agent's state looks like on disk and on the wire.
 *
 * <p>Pekko hands this an {@code Object} and a manifest string, so the erased type has to be
 * re-established somewhere. It happens ONCE, at harness creation, where {@code O} is still
 * statically known — {@link StateTypes} records a {@link JavaType} there and this class only looks
 * it up. That is why there is no cast here, no {@code TypeReference}, and above all no {@code
 * Class.forName}: a manifest can name a type this system already knows, and nothing else.
 *
 * <p>The manifest names the AGENT TYPE rather than the observation's Java class. A package move
 * must not orphan stored state; an agent type rename already means exactly that, and is documented
 * as such. It also matches what Pekko writes as the persistence id prefix, so a row and its
 * manifest name the same thing.
 */
public final class StateSerializer extends SerializerWithStringManifest {

  static final String PREFIX = "agent-state-v1:";
  static final String TURN = "turn-state-v1";
  private static final int IDENTIFIER = 918_302;

  private final StateTypes types;
  private final ObjectMapper mapper;

  /** The constructor Pekko builds reflectively from configuration. */
  public StateSerializer(ExtendedActorSystem system) {
    this(StateTypes.of(Adapter.toTyped(system)));
  }

  /** For reading state back outside an actor system — a page, a repair tool, a test. */
  public StateSerializer(StateTypes types) {
    this.types = types;
    this.mapper = types.mapper();
  }

  /** Convenience for a typed system. */
  public static StateSerializer on(ActorSystem<?> system) {
    return new StateSerializer(StateTypes.of(system));
  }

  @Override
  public int identifier() {
    return IDENTIFIER;
  }

  @Override
  public String manifest(Object o) {
    if (o instanceof AgentState<?> state) {
      return PREFIX + state.agentType().name();
    }
    if (o instanceof TurnState) {
      // No agent type in the manifest, because turn state is not generic -- the observation
      // boundary died at the agent, so there is no erased type here to re-establish.
      return TURN;
    }
    throw new IllegalArgumentException("not engine state: " + o.getClass());
  }

  @Override
  public byte[] toBinary(Object o) {
    try {
      return mapper.writeValueAsBytes(o);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + o.getClass(), e);
    }
  }

  @Override
  public Object fromBinary(byte[] bytes, String manifest) {
    if (TURN.equals(manifest)) {
      try {
        return mapper.readValue(bytes, TurnState.class);
      } catch (IOException e) {
        throw new UncheckedIOException("could not read turn state", e);
      }
    }
    if (!manifest.startsWith(PREFIX)) {
      throw new IllegalArgumentException("unknown state manifest: " + manifest);
    }
    String agentType = manifest.substring(PREFIX.length());
    JavaType type = types.stateType(agentType);
    try {
      return mapper.readValue(bytes, type);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read state for " + agentType, e);
    }
  }
}
