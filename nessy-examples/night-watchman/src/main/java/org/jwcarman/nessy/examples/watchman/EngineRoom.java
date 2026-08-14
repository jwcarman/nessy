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

import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * The engine room's synthetic vitals (spec §2): a seeded random walk, so the story is reproducible,
 * with the bilge deliberately biased upward so a demo run is guaranteed its arc — quiet rounds, a
 * trend, an alarm — within five-to-eight minutes at the default cadence. Fake and obviously so, the
 * coupon-tool ethos. {@code read()} is synchronized out of caution; the default single-threaded
 * scheduler already serializes rounds.
 */
@Component
public class EngineRoom {

  /** One reading of the three gauges, each rounded to one decimal place. */
  public record Vitals(double boilerPressurePsi, double bilgeLevelCm, double hullStressMpa) {}

  private static final long DEFAULT_SEED = 7L;

  private final Random walk;
  private double boiler = 180.0;
  private double bilge = 12.0;
  private double hull = 40.0;

  public EngineRoom() {
    this(DEFAULT_SEED);
  }

  EngineRoom(long seed) {
    this.walk = new Random(seed);
  }

  /** Advances the walk one step and reads all three gauges. */
  public synchronized Vitals read() {
    boiler = clamp(boiler + walk.nextGaussian() * 2.0, 150.0, 260.0);
    bilge = clamp(bilge + walk.nextGaussian() * 1.5 + 3.5, 0.0, 100.0);
    hull = clamp(hull + walk.nextGaussian(), 20.0, 90.0);
    return new Vitals(round1(boiler), round1(bilge), round1(hull));
  }

  private static double clamp(double value, double floor, double ceiling) {
    return Math.min(ceiling, Math.max(floor, value));
  }

  private static double round1(double value) {
    return Math.round(value * 10.0) / 10.0;
  }
}
