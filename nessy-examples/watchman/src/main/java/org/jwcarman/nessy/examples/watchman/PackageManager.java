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

import java.util.List;

/**
 * Which package manager this host has, chosen once by feature detection and handed to the two tools
 * that need it.
 *
 * <p>It exists because {@code apt} and {@code dnf} disagree about what success means: {@code dnf
 * check-update} exits <b>100</b> when there are updates to apply and 0 when there are none, so a
 * tool that treated a non-zero exit as a failure would report "no updates" exactly when there are
 * some. That is one line of difference and one very wrong answer, which is enough to make it a type
 * rather than a boolean.
 */
public enum PackageManager {

  /** Debian and friends. */
  APT(List.of("apt", "list", "--upgradable"), List.of("apt-get", "-y", "upgrade")),

  /** Fedora and friends. */
  DNF(List.of("dnf", "check-update"), List.of("dnf", "-y", "upgrade"));

  private final List<String> check;
  private final List<String> upgrade;

  PackageManager(List<String> check, List<String> upgrade) {
    this.check = check;
    this.upgrade = upgrade;
  }

  /** The argv that lists what is upgradable. */
  public List<String> check() {
    return check;
  }

  /** The argv that actually applies the updates — the line the approval page shows. */
  public List<String> upgrade() {
    return upgrade;
  }

  /** Whether {@code exitCode} from {@link #check()} means the command ran, updates or not. */
  public boolean checkRan(int exitCode) {
    return switch (this) {
      case APT -> exitCode == 0;
      case DNF -> exitCode == 0 || exitCode == 100;
    };
  }
}
