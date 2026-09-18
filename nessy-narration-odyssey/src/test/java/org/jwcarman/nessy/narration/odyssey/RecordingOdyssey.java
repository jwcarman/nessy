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
package org.jwcarman.nessy.narration.odyssey;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.odyssey.core.Odyssey;
import org.jwcarman.odyssey.core.OdysseyStream;
import org.jwcarman.odyssey.core.SubscriberCustomizer;
import org.jwcarman.odyssey.core.TtlPolicy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** An Odyssey that remembers what was published to which stream, and serves nothing. */
final class RecordingOdyssey implements Odyssey {

  record Published(String stream, Class<?> type, String eventName, Object data) {}

  final List<Published> published = new ArrayList<>();
  TtlPolicy lastTtl;

  @Override
  public <T> OdysseyStream<T> stream(String name, Class<T> type, TtlPolicy ttl) {
    lastTtl = ttl;
    return stream(name, type);
  }

  @Override
  public <T> OdysseyStream<T> stream(String name, Class<T> type) {
    return new OdysseyStream<>() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String publish(T data) {
        return publish(null, data);
      }

      @Override
      public String publish(String eventType, T data) {
        published.add(new Published(name, type, eventType, data));
        return String.valueOf(published.size());
      }

      @Override
      public SseEmitter subscribe() {
        throw new UnsupportedOperationException("recording only");
      }

      @Override
      public SseEmitter subscribe(SubscriberCustomizer<T> customizer) {
        throw new UnsupportedOperationException("recording only");
      }

      @Override
      public SseEmitter resume(String lastEventId) {
        throw new UnsupportedOperationException("recording only");
      }

      @Override
      public SseEmitter resume(String lastEventId, SubscriberCustomizer<T> customizer) {
        throw new UnsupportedOperationException("recording only");
      }

      @Override
      public SseEmitter replay(int count) {
        throw new UnsupportedOperationException("recording only");
      }

      @Override
      public SseEmitter replay(int count, SubscriberCustomizer<T> customizer) {
        throw new UnsupportedOperationException("recording only");
      }

      @Override
      public void complete() {
        // a recording is never completed; it is read
      }

      @Override
      public void delete() {
        // nor deleted
      }
    };
  }
}
