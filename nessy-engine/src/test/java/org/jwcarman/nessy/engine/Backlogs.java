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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.message.UserMessage;

/** A {@link BacklogStore} built over a real constructor, for a test that just needs one. */
final class Backlogs {

  private Backlogs() {}

  /** Keeps everything offered, in arrival order — the simplest coalescer that is still honest. */
  private static BacklogCoalescer<String> keepAll() {
    return (waiting, arrival) -> {
      List<BacklogItem<String>> all = new ArrayList<>(waiting);
      all.add(arrival);
      return all;
    };
  }

  /** A {@code BacklogStore<String>} over {@code dataSource}, for a test that needs no more. */
  static BacklogStore<String> ofStrings(DataSource dataSource) {
    ObjectMapper mapper = EngineMapper.create();
    return new BacklogStore<>(
        dataSource,
        new Claims(dataSource),
        JsonCodec.of(mapper, String.class),
        JsonCodec.of(mapper, UserMessage.class),
        UserMessage::of,
        keepAll(),
        Clock.system(ZoneOffset.UTC));
  }
}
