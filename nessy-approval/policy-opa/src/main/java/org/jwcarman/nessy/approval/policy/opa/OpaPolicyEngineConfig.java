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
package org.jwcarman.nessy.approval.policy.opa;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

/**
 * How one {@link OpaPolicyEngine} is put together — the same fluent-into-a-{@code Consumer} shape
 * as {@code HarnessConfig} and {@code PolicyApproverConfig}.
 */
public interface OpaPolicyEngineConfig {

  /** Where OPA listens, e.g. {@code http://localhost:8181}. Required. */
  OpaPolicyEngineConfig url(String url);

  /**
   * The RULE to ask, in slash form: {@code nessy/tools/decision} asks {@code
   * data.nessy.tools.decision}. Required.
   *
   * <p>A rule rather than a package, and one carrying a {@code default} — that is what makes a
   * missing {@code result} mean "this policy is not answering" instead of "no".
   */
  OpaPolicyEngineConfig decisionPath(String decisionPath);

  /** Defaults to a fresh mapper. */
  OpaPolicyEngineConfig objectMapper(ObjectMapper mapper);

  /** What the policy is written against. Defaults to {@link InputRenderer#standard}. */
  OpaPolicyEngineConfig renderer(InputRenderer renderer);

  /** How the answer is read. Defaults to {@link DecisionInterpreter#effectStyle}. */
  OpaPolicyEngineConfig interpreter(DecisionInterpreter interpreter);

  /** How long one decision may take. Defaults to 5 seconds. */
  OpaPolicyEngineConfig timeout(Duration timeout);

  /** How long to spend opening a connection. Defaults to 2 seconds. */
  OpaPolicyEngineConfig connectTimeout(Duration connectTimeout);
}
