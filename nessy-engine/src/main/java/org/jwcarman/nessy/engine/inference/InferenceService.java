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
package org.jwcarman.nessy.engine.inference;

import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * One inference on an agent's behalf: read its story, choose what to send, send it.
 *
 * <p>The agent-facing half. It knows agents and history and nothing about a provider's protocol,
 * exactly as {@link InferenceProvider} knows the protocol and nothing about agents. Everything that
 * holds both sides at once is in one place, and it is three lines long.
 */
@FunctionalInterface
public interface InferenceService {

  InferenceResult infer(InferenceInvocation invocation);
}
