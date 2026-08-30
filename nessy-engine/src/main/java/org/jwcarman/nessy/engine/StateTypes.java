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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Extension;
import org.apache.pekko.actor.typed.ExtensionId;
import org.jwcarman.nessy.api.AgentType;

/**
 * Where the observation type is remembered, so the serializer never has to guess it.
 *
 * <p>Erasure means an {@code AgentState<O>} coming off a mailbox or out of a database is just an
 * {@code AgentState}, and Jackson would rebuild its backlog as maps. The type is known in exactly
 * one place — harness creation, where {@code Class<O>} is a parameter — so it is turned into a
 * {@link JavaType} THERE, while the compiler can still check it, and only looked up later.
 *
 * <p>That is why nothing here resolves a class from a string. A manifest names an agent type this
 * system was told about; it cannot name a class into existence.
 *
 * <p>An {@link Extension} because Pekko builds serializers reflectively from configuration, handing
 * them only the actor system — so the system is the only place a harness and a serializer can both
 * reach.
 */
public final class StateTypes implements Extension {

  private static final ExtensionId<StateTypes> ID =
      new ExtensionId<>() {
        @Override
        public StateTypes createExtension(ActorSystem<?> system) {
          return new StateTypes();
        }
      };

  private final Map<String, JavaType> states = new ConcurrentHashMap<>();
  private final ObjectMapper mapper = EngineMapper.create();

  public static StateTypes of(ActorSystem<?> system) {
    return ID.apply(system);
  }

  /** Called once per harness, where {@code O} is still statically known. */
  public <O> void register(AgentType agentType, Class<O> observationType) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(observationType, "observationType must not be null");
    states.put(
        agentType.name(),
        mapper.getTypeFactory().constructParametricType(AgentState.class, observationType));
  }

  ObjectMapper mapper() {
    return mapper;
  }

  JavaType stateType(String agentTypeName) {
    JavaType type = states.get(agentTypeName);
    if (type == null) {
      throw new IllegalStateException(
          "no agent type \"'"
              + agentTypeName
              + "\"' is registered on this system: state written by a harness this process never"
              + " created");
    }
    return type;
  }
}
