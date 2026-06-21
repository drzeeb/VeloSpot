# Security Policy

The VeloSpot team takes the security of the app and the privacy of its users
seriously. Thank you for helping keep VeloSpot and its users safe.

## Supported versions

Security fixes are always applied to the **latest released version** of VeloSpot.
Please make sure you are running the most recent release before reporting an
issue.

| Version        | Supported          |
| -------------- | ------------------ |
| Latest release | :white_check_mark: |
| Older releases | :x:                |

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions, or pull requests.**

Instead, report them privately using one of the following channels:

- **Preferred:** Open a private report through GitHub's
  [Security Advisories](https://github.com/drzeeb/VeloSpot/security/advisories/new)
  ("Report a vulnerability").

Please include as much of the following information as possible to help us
understand and resolve the issue quickly:

- The type of issue (e.g. data exposure, insecure storage, injection, etc.).
- The component affected (e.g. routing, ride recording, map, storage).
- Step-by-step instructions to reproduce the issue.
- The app version, flavour (Google Play / F-Droid / source build), Android
  version and device, if relevant.
- Proof-of-concept code, logs or screenshots, if available.
- The potential impact of the vulnerability.

## What to expect

- **Acknowledgement:** We aim to acknowledge your report within **72 hours**.
- **Assessment:** We will investigate and keep you informed of our progress.
- **Resolution:** Once a fix is ready, we will publish a new release and, where
  appropriate, a security advisory.
- **Credit:** With your permission, we are happy to credit you for the
  responsible disclosure.

## Disclosure policy

We follow a **coordinated disclosure** process. Please give us a reasonable
amount of time to investigate and release a fix before any public disclosure.
We will work with you to determine an appropriate disclosure timeline.

## Scope

VeloSpot is a privacy-focused, offline-first Android app: all user data
(favourites, saved places and recorded rides) stays exclusively on the device,
and cloud backup is disabled. Issues that are particularly relevant include:

- Unintended exposure or leakage of user data off the device.
- Insecure handling of the optional network calls (address search / reverse
  geocoding via Nominatim, the one-time BRouter segment download, and map
  tiles).
- Insecure local data storage that could be accessed by other apps.

Thank you for helping keep VeloSpot secure! 🚲

