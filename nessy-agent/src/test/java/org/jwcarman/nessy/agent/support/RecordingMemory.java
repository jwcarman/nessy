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
package org.jwcarman.nessy.agent.support;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.Memory;

/** Remembers in order; recall is an executor concern the shell never touches. */
public final class RecordingMemory implements Memory {

  private final List<Message> remembered = new ArrayList<>();

  @Override
  public void remember(Message message) {
    remembered.add(message);
  }

  @Override
  public Context recall() {
    return Context.of(List.copyOf(remembered));
  }

  public List<Message> remembered() {
    return List.copyOf(remembered);
  }
}
