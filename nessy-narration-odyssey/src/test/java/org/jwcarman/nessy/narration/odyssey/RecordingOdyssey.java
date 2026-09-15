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

  record Published(String stream, String eventName, Object data) {}

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
        published.add(new Published(name, eventType, data));
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
      public void complete() {}

      @Override
      public void delete() {}
    };
  }
}
