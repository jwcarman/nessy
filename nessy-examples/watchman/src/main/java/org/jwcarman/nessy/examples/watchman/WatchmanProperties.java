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
package org.jwcarman.nessy.examples.watchman;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything an operator tunes, in the same place every other Spring property lives. */
@ConfigurationProperties(prefix = "watchman")
public class WatchmanProperties {

  /** How long a proposal may wait for a human before it is denied on their behalf. */
  private Duration approvalTerm = Duration.ofDays(3);

  /** How long the web layer waits for the agent to confirm a decision is durable. */
  private Duration askTimeout = Duration.ofSeconds(10);

  private String user = "";
  private String password = "";

  /** Where the model lives. Defaults to LM Studio on this box. */
  private String modelUrl = "http://localhost:1234/v1";

  private String modelId = "qwen/qwen3.6-35b-a3b";

  private String modelApiKey = "lm-studio";

  /** Scripted mode spends nothing and reaches nothing; the tests and the demo use it. */
  private boolean scripted = false;

  /**
   * How much of the conversation may go into a prompt.
   *
   * <p>Without this the prompt grows with the transcript forever — the expensive curve, paid in
   * tokens on every call and fatal to the context window long before the database notices.
   */
  private long contextBudgetTokens = 8000;

  /** The model's own output budget for one turn. */
  private int maxTokens = 4096;

  public long getContextBudgetTokens() {
    return contextBudgetTokens;
  }

  public void setContextBudgetTokens(long contextBudgetTokens) {
    this.contextBudgetTokens = contextBudgetTokens;
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
  }

  public Duration getApprovalTerm() {
    return approvalTerm;
  }

  public void setApprovalTerm(Duration approvalTerm) {
    this.approvalTerm = approvalTerm;
  }

  public Duration getAskTimeout() {
    return askTimeout;
  }

  public void setAskTimeout(Duration askTimeout) {
    this.askTimeout = askTimeout;
  }

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getModelUrl() {
    return modelUrl;
  }

  public void setModelUrl(String modelUrl) {
    this.modelUrl = modelUrl;
  }

  public String getModelId() {
    return modelId;
  }

  public void setModelId(String modelId) {
    this.modelId = modelId;
  }

  public String getModelApiKey() {
    return modelApiKey;
  }

  public void setModelApiKey(String modelApiKey) {
    this.modelApiKey = modelApiKey;
  }

  public boolean isScripted() {
    return scripted;
  }

  public void setScripted(boolean scripted) {
    this.scripted = scripted;
  }
}
