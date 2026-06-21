# Contributing to VeloSpot

First off, thank you for taking the time to contribute! 🚲

VeloSpot is a privacy-focused, offline-first Android app that helps cyclists
find bike parking across Germany, France and Luxembourg. The following is a set
of guidelines for contributing to the project. These are mostly guidelines, not
strict rules — use your best judgement, and feel free to propose changes to this
document in a pull request.

## Table of contents

- [Code of conduct](#code-of-conduct)
- [How can I contribute?](#how-can-i-contribute)
- [Development setup](#development-setup)
- [Building and testing](#building-and-testing)
- [Coding guidelines](#coding-guidelines)
- [Commit messages](#commit-messages)
- [Pull request process](#pull-request-process)
- [Privacy first](#privacy-first)

## Code of conduct

This project and everyone participating in it is governed by our
[Code of Conduct](./CODE_OF_CONDUCT.md). By participating, you are expected to
uphold this code. Please report unacceptable behaviour as described there.

## How can I contribute?

### Reporting bugs

Bugs are tracked as [GitHub issues](https://github.com/drzeeb/VeloSpot/issues).
Before opening one, please search the existing issues to avoid duplicates. When
filing a report, use the **🐛 Bug report** template and include as much detail
as possible (steps to reproduce, app version, flavour, Android version, etc.).

### Suggesting features

Feature ideas are also welcome as GitHub issues — please use the
**✨ Feature request** template. Explain the problem you are trying to solve, not
just the solution, so we can discuss the best approach together.

### Security issues

**Do not** report security vulnerabilities through public issues. Please follow
the process described in [SECURITY.md](./SECURITY.md).

### Contributing code

Pull requests are very welcome! For larger changes, please open an issue first
to discuss what you would like to change, so we can align before you invest a lot
of time.

## Development setup

VeloSpot is a standard Android project written in **Kotlin** with **Jetpack
Compose** and **MapLibre**, built with **Gradle (Kotlin DSL)**.

**Requirements:**

- **JDK 17** (the CI uses Temurin 17).
- **Android Studio** (latest stable recommended) or the Android SDK command-line
  tools.
- **Git with submodule support** — the bundled BRouter offline-routing engine is
  a submodule.

**Clone the repository (including submodules):**

```bash
git clone --recurse-submodules https://github.com/drzeeb/VeloSpot.git
cd VeloSpot
```

If you already cloned without submodules:

```bash
git submodule update --init --recursive
```

## Building and testing

The project has two product flavours under the `distribution` dimension:

- **`googlePlay`** — uses Google Play Services (Fused location).
- **`fdroid`** — fully free/open-source, no Google dependencies. This is the
  canonical flavour used in CI.

Common commands (use `gradlew.bat` on Windows):

```bash
# Build the F-Droid debug APK (the canonical CI build, no GMS required)
./gradlew :app:assembleFdroidDebug

# Run the unit tests
./gradlew :app:testFdroidDebugUnitTest

# Run Android Lint
./gradlew :app:lintFdroidDebug
```

Please make sure unit tests and Lint pass locally before opening a pull request —
the same checks run in CI.

## Coding guidelines

- Follow the existing code style and the official
  [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Keep changes focused — one logical change per pull request.
- Prefer small, pure, well-tested functions; add unit tests for new logic where
  it makes sense (the project already has good coverage for things like routing
  and ride statistics).
- Keep user-facing strings localised — VeloSpot ships in **8 languages**, so new
  strings must be added to `strings.xml` (English) and, where possible, the other
  translations.
- Avoid adding new third-party dependencies unless necessary, and never add
  proprietary dependencies to the `fdroid` flavour.

## Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/) for commit
messages and PR titles, for example:

```
feat(map): cluster favourite markers at low zoom
fix(navigation): reliably detect arrival for every destination
docs: add contributing guide
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`.

## Pull request process

1. Fork the repository and create a feature branch from `main`
   (e.g. `feat/marker-clustering`).
2. Make your changes, following the coding guidelines above.
3. Add or update tests as appropriate, and make sure tests + Lint pass locally.
4. Update [`CHANGELOG.md`](./CHANGELOG.md) under the `## [Unreleased]` section.
5. Update documentation (README, privacy policy, etc.) where relevant.
6. Open a pull request and fill out the PR template. Link any related issues
   (e.g. "Closes #123").
7. Make sure the CI checks pass. A maintainer will review your PR and may request
   changes before merging.

## Privacy first

VeloSpot's core promise is that **all user data stays exclusively on the
device** — favourites, saved places and recorded GPS rides are never uploaded,
and cloud backup is disabled. When contributing, please:

- Never introduce code that sends user data off the device without explicit
  disclosure.
- Document any new network connection in the privacy policy
  ([`PRIVACY.md`](./PRIVACY.md), `docs/PRIVACY.md`, `docs/privacy.html`).
- Keep both flavours' privacy properties intact.

Thank you for helping make VeloSpot better! 🚲

