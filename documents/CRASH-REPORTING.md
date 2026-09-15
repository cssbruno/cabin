# Optional Sentry reporting

## Why

Cabin already has local health reports. Optional remote crash and recovery reports
make problems from a later head-unit test visible without copying raw logs.
Sentry helps diagnose software failures; it does not establish camera, radar, MCU
or CAN compatibility.

## Configuration

- SDK: `io.sentry:sentry-android-core:8.56.0`, pinned from Maven Central.
- Provide `CABIN_SENTRY_DSN` when building. For GitHub releases, add the repository
  secret of the same name. This is the project ingestion URL, not an auth token.
- Reporting defaults off. Enable **Settings → Logs → Share crash reports** on the
  device. A build without a DSN shows reporting as unavailable.
- Tap **Send test report** and verify `TEST_REPORT` in the configured Sentry project.
  “Queued” is not confirmation of server receipt. No live ingestion test has been
  performed without the user's project DSN.
- Existing local report export continues to work without Sentry.

## Data boundary

Events are rebuilt from an allowlist before sending: app release, build environment,
Android API level, exception class/module, stack frame class/function/source filename
and line number, handled status, and fixed connection-event breadcrumbs. Exception
messages, arbitrary event messages, requests, users, extra data, device contexts,
stack variables and absolute source paths are omitted.

Automatic breadcrumbs, screenshots, view hierarchies, raw ANR dumps, historical ANR
collection, native crash collection, session tracking, performance tracing, logs,
metrics and scope persistence are disabled. The core artifact does not include the
Replay or NDK integrations. No audio, GPS, phone identity, SSID, media metadata or
raw CAN payloads are attached. Like any remote HTTPS service, the ingestion service
can observe the connection's IP address.

The SDK handles Java/Kotlin crashes and current ANR detection. Typed breadcrumbs
cover projection states and Joying starts/retries/stops. Exhausted Joying recovery
sends a warning; ordinary transitions remain breadcrumbs attached to later events.

At most 20 envelopes and 40 breadcrumbs are configured. SDK network I/O uses its
workers. Queued reports live in app-private no-backup storage. Reporting consent is
excluded from Android backup/device transfer. Disabling closes the SDK and deletes
its local cache; already transmitted or in-flight reports cannot be recalled.

## Release stack traces

The release workflow generates `CABIN_SENTRY_MAPPING_UUID`, embeds it in events,
and preserves `sentry-mapping-uuid.txt` and the matching R8 `mapping.txt` in the
`cabin-release` CI artifact. Retain these together. Upload the mapping with that UUID
to the Sentry project using an authorized Sentry account/tool before expecting
readable production stack traces. Mapping upload is not automated yet; no account,
project slug or upload token has been supplied. Never put an upload auth token in
an APK. Debug builds are unobfuscated.

## Validation

Automated tests exercise removal of seeded private fields, preservation of stack
locations, rejection of unknown breadcrumbs, fixed event names, default-off
preferences, and deletion of queued files on disable. Device-side ingestion and
ANR/crash testing remain to be performed on a DSN-configured build.

References: [Android SDK](https://docs.sentry.io/platforms/android/),
[pinned SDK source](https://repo.maven.apache.org/maven2/io/sentry/sentry-android-core/8.56.0/sentry-android-core-8.56.0-sources.jar).
