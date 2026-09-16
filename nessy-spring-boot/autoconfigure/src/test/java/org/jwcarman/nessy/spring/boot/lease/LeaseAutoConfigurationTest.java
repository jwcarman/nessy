package org.jwcarman.nessy.spring.boot.lease;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.lease.Leases;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("The lease auto-configuration")
class LeaseAutoConfigurationTest {

  @Test
  void a_leases_bean_appears_once_the_tables_exist() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaseAutoConfiguration.class))
        .withBean(DataSource.class, PGSimpleDataSource::new)
        .withBean(
            NessyAutoConfiguration.NessySchema.class,
            () -> new NessyAutoConfiguration.NessySchema(false))
        .run(context -> assertThat(context).hasSingleBean(Leases.class));
  }
}
