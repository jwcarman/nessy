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
package org.jwcarman.nessy.agent;

/** The recipe's name. The type is code; the id is data (spec §1.1). */
public record AgentType(String name) {

  public AgentType {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("agent type name must not be blank");
    }
    if (name.indexOf(':') >= 0) {
      throw new IllegalArgumentException(
          "agent type name must not contain ':': \""
              + name
              + "\" — kind-name hygiene (computation-identity spec §3): the type threads into"
              + " kind strings (computation/<agentType>, approval/<agentType>,"
              + " outbox/<agentType>), and nothing parses those apart anymore, but a colon in the"
              + " type would still make a kind string look like it carries a delimiter it does"
              + " not — kind names stay boring on purpose");
    }
  }

  public static AgentType of(String name) {
    return new AgentType(name);
  }
}
