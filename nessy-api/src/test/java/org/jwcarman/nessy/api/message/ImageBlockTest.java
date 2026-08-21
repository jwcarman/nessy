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
package org.jwcarman.nessy.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The image block: a media type and its base64 payload, both mandatory. */
class ImageBlockTest {

  @Test
  void carries_its_media_type_and_payload() {
    ImageBlock block = new ImageBlock("image/png", "aGVsbG8=");

    assertThat(block.mediaType()).isEqualTo("image/png");
    assertThat(block.base64Data()).isEqualTo("aGVsbG8=");
    assertThat(block).isInstanceOf(ContentBlock.class);
  }

  @Test
  void a_null_media_type_is_rejected() {
    assertThatThrownBy(() -> new ImageBlock(null, "aGVsbG8="))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("mediaType");
  }

  @Test
  void a_null_payload_is_rejected() {
    assertThatThrownBy(() -> new ImageBlock("image/png", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("base64Data");
  }
}
