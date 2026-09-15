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
