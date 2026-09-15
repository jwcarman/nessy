package org.jwcarman.nessy.spring.boot.lease;

import javax.sql.DataSource;
import org.jwcarman.nessy.lease.JdbcLeases;
import org.jwcarman.nessy.lease.Leases;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Leases over the application's database, when {@code nessy-lease} is on the classpath. After the
 * starter's own auto-configuration, whose schema step creates the table.
 */
@AutoConfiguration(after = NessyAutoConfiguration.class)
@ConditionalOnClass(JdbcLeases.class)
public class LeaseAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Leases nessyLeases(DataSource dataSource, NessyAutoConfiguration.NessySchema schema) {
    return new JdbcLeases(dataSource);
  }
}
