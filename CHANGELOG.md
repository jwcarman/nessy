# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Nessy has not yet made a public release. The API is unstable and may change
without notice until the 1.0.0 release.

## [Unreleased]

### Changed

- JPMS module descriptor withdrawn: white-box tests (same-package,
  reflectively instantiated by JUnit) fail on the module path in IDEs
  (`InaccessibleObjectException ... does not "opens" ... to
  org.junit.platform.commons`), and the fixes — per-developer IDE config or
  test-only `opens` in the production descriptor — cost more than the
  descriptor buys. Both jars carry `Automatic-Module-Name`
  (`org.jwcarman.nessy.core` / `org.jwcarman.nessy.testing`); the
  api/spi/internal boundary stands on package convention until revisited
  pre-1.0.
