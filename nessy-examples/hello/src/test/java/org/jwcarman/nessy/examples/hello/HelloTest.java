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
package org.jwcarman.nessy.examples.hello;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Hello is its own test: the offline default build runs this, so the README's five-minute promise
 * stays honest with no key and no network, not just at the moment this module was written.
 */
class HelloTest {

  @Test
  void the_scripted_conversation_settles_on_the_advertised_answer() {
    String line = Hello.run();

    assertThat(line).isEqualTo("The answer is 4. (COMPLETE)");
  }
}
