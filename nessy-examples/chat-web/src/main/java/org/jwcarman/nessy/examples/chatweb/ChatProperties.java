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
package org.jwcarman.nessy.examples.chatweb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Which model this chat talks to, and where it lives. */
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

  /** Base URL of an OpenAI-compatible endpoint. Defaults to LM Studio on this machine. */
  private String modelUrl = "http://localhost:1234/v1";

  /** The key for that endpoint. Local runtimes ignore it but the client insists on one. */
  private String modelApiKey = "not-needed";

  private String modelId = "qwen/qwen3.6-35b-a3b";

  public String getModelUrl() {
    return modelUrl;
  }

  public void setModelUrl(String modelUrl) {
    this.modelUrl = modelUrl;
  }

  public String getModelApiKey() {
    return modelApiKey;
  }

  public void setModelApiKey(String modelApiKey) {
    this.modelApiKey = modelApiKey;
  }

  public String getModelId() {
    return modelId;
  }

  public void setModelId(String modelId) {
    this.modelId = modelId;
  }
}
