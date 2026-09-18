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

/**
 * What is the watchman's to decide. The model, the provider and the tables are the starter's
 * ({@code nessy.*}, {@code openai.*}); this is the rest.
 */
@ConfigurationProperties(prefix = "watchman")
public class WatchmanProperties {

  private Duration approvalTerm = Duration.ofDays(3);
  private boolean scripted = false;

  public Duration getApprovalTerm() {
    return approvalTerm;
  }

  public void setApprovalTerm(Duration approvalTerm) {
    this.approvalTerm = approvalTerm;
  }

  public boolean isScripted() {
    return scripted;
  }

  public void setScripted(boolean scripted) {
    this.scripted = scripted;
  }
}
