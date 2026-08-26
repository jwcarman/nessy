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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One account, from properties, over HTTP basic (spec §2.2).
 *
 * <p>It is a LAN, which is a reason to keep this small and not a reason to leave it open: the two
 * buttons on this page restart production services on a real box. {@code watchman.user} and {@code
 * watchman.password} are required and have no defaults — see {@link WatchmanProperties}, which
 * fails the context rather than letting the application boot with a password anyone could guess
 * from the source.
 *
 * <p>The authenticated name is what {@link ApprovalsController} hands the desk as the principal, so
 * this is also where the audit trail's names come from.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  /**
   * {@code {noop}} is deliberate and correct here: the password arrives from configuration in
   * plaintext, so pretending to hash it would only hide that. The mitigation is that it is required
   * configuration, not a shipped default.
   */
  @Bean
  public UserDetailsService watchmanUser(WatchmanProperties properties) {
    return new InMemoryUserDetailsManager(
        User.withUsername(properties.user())
            .password("{noop}" + properties.password())
            .roles("WATCHMAN")
            .build());
  }

  /**
   * Everything needs the password except the webjar the page loads its one script from and the
   * health endpoint a monitor polls. Neither reveals anything, and a login prompt for a stylesheet
   * is how people end up disabling security altogether.
   */
  @Bean
  public SecurityFilterChain watchmanSecurity(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/webjars/**", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
  }
}
