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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The tools an agent can call, as the engine sees them: a name, whether it needs a human, and a way
 * to run it.
 *
 * <p>This exists to keep the engine from knowing what a tool IS. Before it, {@code ToolWorker} took
 * a {@link CommandRunner} and so believed every tool was a shell command — which is true of the
 * watchman and of nothing else. An implementation binds whatever machinery it likes; the actors
 * above it only ever see this.
 *
 * <p>Deliberately provisional. The real seam is {@code Tool} in {@code nessy-api}, wired through
 * {@code HarnessConfig}; this is the narrow shape that lets the engine move out of the example
 * first, and it is replaced rather than grown.
 */
public interface AgentTools {

  /** Whether calling {@code tool} requires a human's approval before it runs. */
  boolean needsApproval(String tool);

  /** A short human-readable description of what this call would do, for the approval page. */
  String action(String tool, String argumentsJson);

  /** Runs the tool and renders its outcome as text. */
  String run(String tool, String argumentsJson);

  /** Parses raw argument JSON, so a malformed payload fails in one known place. */
  JsonNode argumentsOf(String argumentsJson);
}
