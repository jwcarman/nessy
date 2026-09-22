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
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.ObservationCoalescer;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.backlog.Backlog;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every codec is created for a sealed root rather than a concrete record, so what matters is that a
 * variant survives a round-trip through the interface: without the type discriminator, decoding
 * would either fail or hand back the wrong record.
 */
class AgentCodecTest {

  private static final ObservationCoalescer<String> KEEP_ALL = ObservationCoalescer.keepAll();

  private final CodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());

  private static final java.time.Instant T0 = java.time.Instant.parse("2026-09-07T12:00:00Z");

  private Codec<AgentEffect> effectCodec() {
    return codecs.create(AgentEffect.class);
  }

  private Codec<HistoryEntry> historyEntryCodec() {
    return codecs.create(HistoryEntry.class);
  }

  /**
   * The document's codec is the one thing that has to know {@code <O>}, and it cannot be captured
   * for a type variable -- {@code new TypeRef<AgentState<O>>() {}} throws. Composing one is what
   * the factory does for every harness, so if this stops working nothing can store state at all.
   */
  private <O> Codec<AgentState<O>> stateCodec(Class<O> observationType) {
    return codecs.create(TypeRef.parameterized(AgentState.class, TypeRef.of(observationType)));
  }

  @Test
  void idleRoundTrips() {
    Codec<AgentState<String>> codec = stateCodec(String.class);

    assertThat(roundTrip(codec, new AgentState.Idle<String>(new Seq(7))))
        .isEqualTo(new AgentState.Idle<String>(new Seq(7)));
  }

  /** The backlog holds the caller's own observations, so this is where {@code <O>} is proven. */
  @Test
  void callingModelRoundTripsCarryingItsUnrenderedBacklog() {
    Codec<AgentState<String>> codec = stateCodec(String.class);
    AgentState<String> state =
        new AgentState.Inferring<>(
            new Seq(3),
            new TurnId(3),
            Backlog.<String>empty().accept(new BacklogItem<>("and another thing", T0), KEEP_ALL));

    assertThat(roundTrip(codec, state)).isEqualTo(state);
  }

  @Test
  void anObservationMessageRoundTrips() {
    HistoryEntry.ObservationReceived message =
        HistoryEntry.ObservationReceived.opening(
            1, HistoryEntry.ObservationReceived.text("what is nessy?"));

    assertThat(roundTrip(historyEntryCodec(), message)).isEqualTo(message);
  }

  /** A failure message has no content at all, so the discriminator is the whole payload. */
  @Test
  void anInferenceFailedEntryRoundTrips() {
    assertThat(
            roundTrip(
                historyEntryCodec(), new HistoryEntry.InferenceFailed(new Seq(2), new TurnId(1))))
        .isEqualTo(new HistoryEntry.InferenceFailed(new Seq(2), new TurnId(1)));
  }

  @Test
  void anAnswerMessageRoundTrips() {
    assertThat(
            roundTrip(
                historyEntryCodec(), HistoryEntry.InferenceAnswered.of(2, 1, "a lake monster")))
        .isEqualTo(HistoryEntry.InferenceAnswered.of(2, 1, "a lake monster"));
  }

  /**
   * The stored effect says only what to do. It carries no messages: the story is read when the call
   * runs, so a row cannot freeze a conversation the agent has since moved past.
   */
  @Test
  void callModelRoundTripsCarryingNothing() {
    AgentEffect effect = new AgentEffect.Infer();

    assertThat(roundTrip(effectCodec(), effect)).isEqualTo(effect);
  }

  private static <T> T roundTrip(Codec<T> codec, T value) {
    return codec.decode(codec.encode(value));
  }

  /** Guards the assumption every other test here leans on: these lists are not shared. */
  @Test
  void aDecodedBacklogIsIndependentOfTheOneEncoded() {
    Codec<AgentState<String>> codec = stateCodec(String.class);
    List<BacklogItem<String>> items = List.of(new BacklogItem<>("first", T0));
    AgentState<String> state =
        new AgentState.Inferring<>(new Seq(5), new TurnId(5), new Backlog.Open<>(items));

    assertThat(roundTrip(codec, state)).isEqualTo(state).isNotSameAs(state);
  }
}
