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
package org.jwcarman.nessy.inference.openai;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.inference.WireNarrator;

/**
 * What a provider said while it was streaming, in the order it said it.
 *
 * <p>One list rather than two, because the interleaving is the thing worth asserting: a model that
 * thinks and then answers should narrate in that order.
 */
final class Narration implements WireNarrator {

  private final List<Fragment> fragments = new ArrayList<>();

  record Fragment(String kind, String text) {

    static Fragment text(String text) {
      return new Fragment("text", text);
    }

    static Fragment thinking(String text) {
      return new Fragment("thinking", text);
    }
  }

  @Override
  public void text(String delta) {
    fragments.add(Fragment.text(delta));
  }

  @Override
  public void thinking(String delta) {
    fragments.add(Fragment.thinking(delta));
  }

  List<Fragment> fragments() {
    return List.copyOf(fragments);
  }

  /** Just the answer, for a test that does not care what the model was thinking. */
  List<String> text() {
    return fragments.stream().filter(f -> "text".equals(f.kind())).map(Fragment::text).toList();
  }
}
