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

import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/** Writes down a model call before it is made and how it came back once it has. */
public interface InferenceRecorder {

  /** Nothing is written down. */
  InferenceRecorder NONE =
      new InferenceRecorder() {
        @Override
        public UUID begin(AgentType agentType, AgentId agentId, InferenceRequest request) {
          return null;
        }

        @Override
        public void end(UUID id, InferenceResult result) {
          // nothing was begun
        }

        @Override
        public void failed(UUID id) {
          // nothing was begun
        }
      };

  /** Before the provider is asked; returns the record's id, or null if nothing was recorded. */
  UUID begin(AgentType agentType, AgentId agentId, InferenceRequest request);

  /** After the provider answered, however it answered. */
  void end(UUID id, InferenceResult result);

  /** The provider threw rather than answered. */
  void failed(UUID id);
}
