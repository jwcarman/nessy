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
package org.jwcarman.nessy.examples.watchman.pekko;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** One user, form login, everything behind it. A LAN page, not a public one. */
@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/webjars/**", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(login -> login.permitAll())
        .csrf(csrf -> csrf.disable());
    return http.build();
  }

  @Bean
  public UserDetailsService users(WatchmanProperties properties) {
    return new InMemoryUserDetailsManager(
        User.withUsername(properties.getUser().isBlank() ? "watchman" : properties.getUser())
            .password(
                "{noop}"
                    + (properties.getPassword().isBlank() ? "watchman" : properties.getPassword()))
            .roles("OPERATOR")
            .build());
  }
}
