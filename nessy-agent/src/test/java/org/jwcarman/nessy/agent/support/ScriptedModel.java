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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/** Replays a scripted event list per stream() call; records each request. */
public final class ScriptedModel implements Model {

  private final List<List<ModelEvent>> scripts;
  private final List<ModelRequest> requests = new ArrayList<>();
  private int next;

  public ScriptedModel(List<List<ModelEvent>> scripts) {
    this.scripts = scripts;
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    requests.add(request);
    List<ModelEvent> script = scripts.get(next++);
    return new ModelStream() {
      @Override
      public Iterator<ModelEvent> iterator() {
        return script.iterator();
      }

      @Override
      public void close() {
        // no resources to release: events are already materialized
      }
    };
  }

  @Override
  public Set<Capability> capabilities() {
    return Set.of();
  }

  @Override
  public String id() {
    return "scripted";
  }

  @Override
  public String provider() {
    return "scripted";
  }

  public List<ModelRequest> requests() {
    return List.copyOf(requests);
  }
}
