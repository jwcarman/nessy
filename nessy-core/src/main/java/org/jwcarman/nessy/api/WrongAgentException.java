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
package org.jwcarman.nessy.api;

/**
 * {@code token} names a wait this {@link org.jwcarman.nessy.Agent} did not mint — the park's own
 * {@link org.jwcarman.nessy.spi.conversation.Parks.Park#agentName()} names a different agent than
 * the one a callback door was called on (design §3, §5). Self-diagnosing on purpose: the message
 * names the token and both agents and spells out the fix — an agent's name is a durable wire
 * contract, so a rename-without-redeploy breaks the first callback loud, rather than misrouting a
 * resolution through the wrong agent's grants and listeners.
 *
 * <p>Every callback door verifies this <em>before</em> appending or driving anything — a refused
 * delivery leaves the conversation exactly as it was.
 *
 * <p>Distinct from {@link UnknownParkTokenException}: that one means the registry has never heard
 * of the token at all; this one means the registry has, and the wait is real, just minted by
 * someone else.
 */
public final class WrongAgentException extends RuntimeException {

  public WrongAgentException(ParkToken token, String parkedByAgentName, String thisAgentName) {
    super(
        "park "
            + token.value()
            + " was minted by agent '"
            + parkedByAgentName
            + "'; this agent is '"
            + thisAgentName
            + "' — an agent's name is a durable wire contract; redeploy under '"
            + parkedByAgentName
            + "' to drain its parks");
  }
}
