# Contributing to Nessy

Thanks for your interest in contributing to Nessy! We welcome pull requests, issues, and feedback from the community.

## How to Contribute

### Reporting Bugs

If you find a bug, please open an issue and include:
- A clear description of the problem
- Steps to reproduce the issue
- Expected vs actual behavior
- Version of the library and relevant environment details (Java version)

### Requesting Features

We're happy to hear your ideas! Before opening a feature request, check if one already exists. If not, open a new issue and include:
- A description of the proposed feature
- Why it would be useful
- Any relevant use cases or examples

### Submitting a Pull Request

If you'd like to contribute code:
1. **Fork** the repository and create a new branch from `main`
2. Make your changes, writing tests if applicable
3. Run the build and tests:
   ```bash
   mvn clean verify
   ```
4. Ensure code quality checks pass:
   ```bash
   mvn spotless:check
   mvn license:check -Plicense
   ```
5. Open a pull request and describe your changes

Please follow idiomatic Java practices and keep your code clean and well-documented. For larger changes, consider opening an issue first to discuss the approach.

## Testing

We maintain high test coverage. When adding new features, please include unit tests for core logic. Tests that spend real tokens against a model provider are tagged `live` and excluded from the default build so `mvn verify` never needs network access to a model provider.

Run tests with:
```bash
mvn clean verify
```

## Code Style and Conventions

- **Java 25+**: Use modern Java features judiciously -- prefer clarity and simplicity
- **Formatting**: Google Java Format (enforced by Spotless)
- **License headers**: Apache 2.0 headers on all source files (enforced by `mvn -Plicense verify`)
- **No `@SuppressWarnings`**: Fix the underlying issue instead
- **No star imports**: Always use explicit single-symbol imports

### Commit Messages

Follow conventional commit format:
- `feat: add support for new tool seam`
- `fix: handle edge case in session resume`
- `docs: add usage examples to README`
- `test: add coverage for approval reducer`
- `refactor: extract shared logic`

## Community Standards

We strive to foster a welcoming and respectful community. By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing to this project, you agree that your contributions will be licensed under the Apache License 2.0.
