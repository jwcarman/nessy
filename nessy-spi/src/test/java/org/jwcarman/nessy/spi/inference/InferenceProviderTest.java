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
package org.jwcarman.nessy.spi.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * What a provider says it is, when it has not said.
 *
 * <p>The name reaches a dashboard as {@code gen_ai.provider.name} and a metric tag has to be the
 * same on every run, which is what rules out the names the JVM gives a lambda or an anonymous
 * class: one carries an address, the other is empty.
 */
class InferenceProviderTest {

  private static final InferenceResult ANSWER =
      new InferenceResult.Answer(List.of(new Block.Text("done")));

  /** A named class answers for itself. */
  private static class LocalProvider implements InferenceProvider {

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      return ANSWER;
    }
  }

  @Test
  void a_named_provider_is_named_for_itself() {
    assertThat(new LocalProvider().providerName()).isEqualTo("LocalProvider");
  }

  /** A lambda's own name carries an address, so it answers for the class that wrote it. */
  @Test
  void a_provider_written_as_a_lambda_is_named_for_the_class_that_wrote_it() {
    InferenceProvider lambda = (_, _) -> ANSWER;

    assertThat(lambda.providerName()).isEqualTo("InferenceProviderTest");
  }

  /** An anonymous class has no simple name at all, so it answers the same way. */
  @Test
  void a_provider_written_as_an_anonymous_class_is_named_the_same_way() {
    InferenceProvider anonymous = new LocalProvider() {};

    assertThat(anonymous.providerName()).isEqualTo("InferenceProviderTest");
  }

  /** An adapter says its vendor, in semconv's spelling, and the default never gets a say. */
  @Test
  void an_adapter_that_says_its_vendor_keeps_it() {
    InferenceProvider vendor =
        new LocalProvider() {
          @Override
          public String providerName() {
            return "gcp.gemini";
          }
        };

    assertThat(vendor.providerName()).isEqualTo("gcp.gemini");
  }

  /** A caller with nobody watching gets the same answer as one who is. */
  @Test
  void a_request_without_a_narrator_is_the_same_call() {
    assertThat(new LocalProvider().infer(null)).isEqualTo(ANSWER);
  }
}
