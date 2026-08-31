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
package org.jwcarman.nessy.memory.pipeline;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.message.Context;

/**
 * One stage of a memory pipeline: the context as built so far, in; the context as it should
 * continue, out.
 *
 * <p>May trim, redact, elide, or add background. It cannot corrupt the conversation: a {@link
 * Context} is built through validated constructors, and an {@code ExchangeMessage} carries its own
 * results, so there is no half-exchange for a stage to leave behind.
 *
 * <p><b>Every stage is required.</b> A stage that throws propagates out of {@code recall} rather
 * than being papered over — the turn fails, the durable machinery retries it later, and the model
 * never sees a context the stage did not bless.
 *
 * <p><b>Nothing a stage produces is remembered.</b> Stages run at recall, on the way to one model
 * call. Their output is not told to the transcript and not folded into any summary: one fresh pass
 * per call, no accumulation, no drift. That is what makes an {@code AmbientMessage} safe to add
 * here — it is always current, because it is always rebuilt.
 */
@FunctionalInterface
public interface ContextTransformer {

  Context transform(AgentId agentId, Context context);
}
