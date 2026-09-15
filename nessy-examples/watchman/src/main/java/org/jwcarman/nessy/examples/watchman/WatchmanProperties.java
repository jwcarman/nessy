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
