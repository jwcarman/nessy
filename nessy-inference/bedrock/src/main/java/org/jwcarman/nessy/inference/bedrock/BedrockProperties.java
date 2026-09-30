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
package org.jwcarman.nessy.inference.bedrock;

import org.jwcarman.nessy.api.VendorProperty;

/**
 * The {@code bedrock.} vendor properties the adapter supports, declared once: the three Converse
 * inference settings that are typed on the AWS request. Set one with {@code
 * property(BedrockProperties.TEMPERATURE, 0.2f)}, or by name and text, as YAML does. A name under
 * the prefix that is not one of them is ignored, and said so once when it is checked.
 */
public final class BedrockProperties {

  /** The sampling temperature. The type, never the range: the vendor says what is valid. */
  public static final VendorProperty<Float> TEMPERATURE =
      VendorProperty.ofFloat("bedrock.inferenceConfig.temperature");

  /** The nucleus sampling cutoff. */
  public static final VendorProperty<Float> TOP_P =
      VendorProperty.ofFloat("bedrock.inferenceConfig.topP");

  private BedrockProperties() {}
}
