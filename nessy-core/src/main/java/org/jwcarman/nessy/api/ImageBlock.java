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
package org.jwcarman.nessy.api;

import java.util.Objects;

/** An image attached to a message, base64-encoded with its media type. */
public record ImageBlock(String mediaType, String base64Data) implements ContentBlock {

  public ImageBlock {
    Objects.requireNonNull(mediaType, "mediaType must not be null");
    Objects.requireNonNull(base64Data, "base64Data must not be null");
  }
}
