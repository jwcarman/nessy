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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.serialization.SerializerWithStringManifest;
import org.jwcarman.nessy.engine.agent.AgentState;

/**
 * What an agent's state looks like on disk and on the wire.
 *
 * <p><b>There is no erased type left to re-establish.</b> {@code AgentState} used to be generic in
 * the observation, so the concrete type had to be recorded at harness creation and looked up here —
 * a whole {@code StateTypes} extension existed for that one job. The backlog is a store now and it
 * owns the observation type, so this state is a turn id, a phase and two ids, and Jackson can read
 * it back with nothing but the class.
 *
 * <p>The manifest is a version rather than a type name, because there is only one type. It is still
 * a compatibility surface: state written by an older engine is read by name.
 */
public final class StateSerializer extends SerializerWithStringManifest {

  static final String AGENT = "agent-state-v2";
  private static final int IDENTIFIER = 918_302;

  private final ObjectMapper mapper = EngineMapper.create();

  /** The constructor Pekko builds reflectively from configuration. */
  public StateSerializer(ExtendedActorSystem system) {
    // The system is what Pekko has to hand; nothing here needs it any more.
  }

  /** For reading state back outside an actor system — a page, a repair tool, a test. */
  public StateSerializer() {
    this(null);
  }

  /** Convenience for a typed system. */
  public static StateSerializer on(ActorSystem<?> system) {
    return new StateSerializer();
  }

  @Override
  public int identifier() {
    return IDENTIFIER;
  }

  @Override
  public String manifest(Object o) {
    if (o instanceof AgentState) {
      return AGENT;
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
    if (!AGENT.equals(manifest)) {
      throw new IllegalArgumentException("unknown state manifest: " + manifest);
    }
    try {
      return mapper.readValue(bytes, AgentState.class);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read agent state", e);
    }
  }
}
