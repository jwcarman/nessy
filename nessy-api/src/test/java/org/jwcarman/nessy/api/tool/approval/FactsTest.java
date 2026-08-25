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
package org.jwcarman.nessy.api.tool.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.authorization.Key;

class FactsTest {

  record Weather(String sky, int degrees) {}

  /** A value Jackson cannot render: no properties, no creator, a self-reference. */
  static final class Unrenderable {
    final Unrenderable self = this;
  }

  private static final Key<Weather> WEATHER = new Key<>(Weather.class, "test.weather");
  private static final Key<String> NOTE = new Key<>(String.class, "test.note");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void aDepositedFactReadsBackTyped() {
    Facts.Deposits deposits = Facts.deposits(mapper);
    deposits.put(WEATHER, new Weather("clear", 21));

    Facts facts = deposits.freeze();

    assertThat(facts.get(WEATHER)).contains(new Weather("clear", 21));
  }

  @Test
  void anAbsentFactIsEmptyNotAnError() {
    Facts facts = Facts.deposits(mapper).freeze();

    assertThat(facts.get(NOTE)).isEqualTo(Optional.empty());
  }

  @Test
  void anUnrenderableValueFailsAtDepositNamingTheKey() {
    Facts.Deposits deposits = Facts.deposits(mapper);
    Key<Unrenderable> key = new Key<>(Unrenderable.class, "test.unrenderable");
    Unrenderable value = new Unrenderable();

    assertThatThrownBy(() -> deposits.put(key, value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("test.unrenderable");
  }

  @Test
  void aNullValueIsRefusedNamingTheKey() {
    Facts.Deposits deposits = Facts.deposits(mapper);

    assertThatThrownBy(() -> deposits.put(NOTE, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("test.note");
  }

  @Test
  void factsRoundTripThroughJsonAndReadBackTypedOnceAttached() throws Exception {
    Facts.Deposits deposits = Facts.deposits(mapper);
    deposits.put(WEATHER, new Weather("rain", 12));
    deposits.put(NOTE, "bring a coat");
    Facts original = deposits.freeze();

    String json = mapper.writeValueAsString(original);
    Facts decoded = mapper.readValue(json, Facts.class).attach(mapper);

    assertThat(decoded.names()).containsExactly("test.note", "test.weather");
    assertThat(decoded.get(WEATHER)).contains(new Weather("rain", 12));
    assertThat(decoded.get(NOTE)).contains("bring a coat");
    assertThat(decoded.raw("test.note").asText()).isEqualTo("bring a coat");
  }

  @Test
  void aDecodedBagReadsRawJsonWithoutAMapperButRefusesTypedReads() throws Exception {
    Facts.Deposits deposits = Facts.deposits(mapper);
    deposits.put(NOTE, "x");
    String json = mapper.writeValueAsString(deposits.freeze());
    Facts decoded = mapper.readValue(json, Facts.class);

    assertThat(decoded.raw("test.note").asText()).isEqualTo("x");
    assertThatThrownBy(() -> decoded.get(NOTE)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aFactThatDoesNotDecodeAsItsKeysTypeFailsNamingTheKey() {
    Facts.Deposits deposits = Facts.deposits(mapper);
    deposits.put(NOTE, "not a weather");
    Facts facts = deposits.freeze();
    Key<Weather> misread = new Key<>(Weather.class, "test.note");

    assertThatThrownBy(() -> facts.get(misread))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("test.note");
  }
}
