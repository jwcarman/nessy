package org.jwcarman.nessy.spring.boot.memory;

import java.time.Duration;
import java.util.List;
import org.jwcarman.nessy.memory.summarizing.HeadSummarizer;
import org.jwcarman.nessy.memory.summarizing.Sweeper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Every {@link HeadSummarizer} an application declares is swept on a schedule. The summariser
 * itself is the application's to declare -- it names an agent type, a store and a provider -- and
 * this only sees that its sweep runs.
 */
@AutoConfiguration
@ConditionalOnClass(HeadSummarizer.class)
@ConditionalOnBean(HeadSummarizer.class)
@EnableConfigurationProperties(SummarizingAutoConfiguration.SummarizingProperties.class)
public class SummarizingAutoConfiguration {

  /**
   * @param sweepInterval how often every summariser looks for heads to summarise
   */
  @ConfigurationProperties("nessy.summaries")
  public record SummarizingProperties(Duration sweepInterval) {
    public SummarizingProperties {
      sweepInterval = sweepInterval == null ? Duration.ofMinutes(1) : sweepInterval;
    }
  }

  @Bean
  @ConditionalOnMissingBean
  public Sweeper nessySummarySweeper(
      List<HeadSummarizer> summarizers, SummarizingProperties properties) {
    return new Sweeper(summarizers, properties.sweepInterval());
  }
}
