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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.message.UserMessage;

/** An observation vocabulary for tests. Nothing about it is known to the engine. */
final class HouseEvents {

  private HouseEvents() {}

  record HouseEvent(String room, String what) {}

  private static final ObjectMapper MAPPER = EngineMapper.INSTANCE;

  static final Codec<HouseEvent> CODEC =
      new Codec<>() {
        @Override
        public byte[] encode(HouseEvent value) {
          try {
            return MAPPER.writeValueAsBytes(value);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }

        @Override
        public HouseEvent decode(byte[] bytes) {
          try {
            return MAPPER.readValue(bytes, HouseEvent.class);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      };

  static final BacklogCoalescer<HouseEvent> KEEP_ALL =
      (waiting, arrival) -> {
        List<BacklogItem<HouseEvent>> all = new ArrayList<>(waiting);
        all.add(arrival);
        return all;
      };

  static final ObservationRenderer<HouseEvent> RENDERER =
      event -> UserMessage.of(event.room() + ": " + event.what());
}
