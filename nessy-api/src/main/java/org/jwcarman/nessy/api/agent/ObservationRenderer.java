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
package org.jwcarman.nessy.api.agent;

import java.util.List;
import org.jwcarman.nessy.api.message.ContentBlock;

/**
 * Translates observations to inference blocks: applied at poll time (§3.7). An empty list declines
 * the observation: the shell discards it and keeps draining (§3.3).
 */
@FunctionalInterface
public interface ObservationRenderer<O> {
  List<ContentBlock> render(O observation);
}
