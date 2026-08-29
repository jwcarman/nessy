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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.serialization.SerializerWithStringManifest;
import org.jwcarman.nessy.spi.codec.CodecPipeline;

/**
 * Our codec, plugged into Pekko. Carried forward from the spike, and it is what keeps {@code
 * pekko-serialization-jackson} — and its hard {@code jackson-databind} version range — off this
 * application's classpath entirely.
 *
 * <p>It earns its keep twice over here: {@link PendingApprovals} reads the very same bytes back out
 * of Postgres to build the approvals page, so the read side and the write side cannot disagree
 * about the format because they are the same object.
 */
public final class StateSerializer extends SerializerWithStringManifest {

  private final EngineCodecs.Pipelines codecs;

  /**
   * The constructor Pekko prefers. Declaring it is the only way a serializer built reflectively
   * from {@code .conf} can reach anything the harness configured — here, the codec pipeline, so
   * actor state is transformed exactly the way everything through {@code Substrate} is.
   */
  public StateSerializer(ExtendedActorSystem system) {
    this.codecs = EngineCodecs.of(system);
  }

  /** For direct use outside an actor system (the approvals page reads these bytes back). */
  public StateSerializer() {
    this.codecs = null;
  }

  private CodecPipeline pipeline() {
    return codecs == null ? CodecPipeline.none() : codecs.pipeline();
  }

  public static final String AGENT_STATE_V2 = "watchman-agent-state-v2";

  private static final int IDENTIFIER = 918_301;

  /**
   * Our mapper: Pekko has no say in how it is built.
   *
   * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is off, and that is a migration decision rather than
   * laziness. A durable-state document is REWRITTEN in place, so there is no event log to replay
   * and no automatic migration: the day a field leaves the state, every row still on disk carries
   * it, and a strict reader turns "we shipped a smaller state" into "the agent cannot load". This
   * was not hypothetical — moving the transcript out of {@link AgentState} made every pre-existing
   * row unreadable until this line existed. Tolerating unknown fields makes a SHRINKING change
   * safe; anything larger still needs {@link #manifest} bumped and a real migration.
   */
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @Override
  public int identifier() {
    return IDENTIFIER;
  }

  @Override
  public String manifest(Object o) {
    if (o instanceof AgentState) {
      return AGENT_STATE_V2;
    }
    throw new IllegalArgumentException("not a watchman state: " + o.getClass());
  }

  @Override
  public byte[] toBinary(Object o) {
    try {
      return pipeline().encode(MAPPER.writeValueAsBytes(o));
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + o.getClass(), e);
    }
  }

  @Override
  public Object fromBinary(byte[] bytes, String manifest) {
    if (!AGENT_STATE_V2.equals(manifest)) {
      throw new IllegalArgumentException("unknown manifest: " + manifest);
    }
    try {
      return MAPPER.readValue(pipeline().decode(bytes), AgentState.class);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + manifest, e);
    }
  }
}
