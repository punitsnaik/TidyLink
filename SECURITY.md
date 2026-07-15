# Security Policy

## Supported versions

Only the latest release of TidyLink is supported with security fixes.

## Reporting a vulnerability

Please report vulnerabilities privately via **GitHub's private vulnerability reporting** ("Report a vulnerability" under the repository's Security tab). Do not open a public issue for security problems.

You can expect an acknowledgement within a week. Please include steps to reproduce and the app/Android version affected.

## Scope notes

- TidyLink has no backend; all data is on-device.
- LLM API keys are entered by the user, stored AES/GCM-encrypted with an Android Keystore key, excluded from backups, and sent only to the endpoint the user configured.
- Reports about the *content* returned by third-party LLM providers or scraped websites are out of scope; reports about how the app handles that content are in scope.
