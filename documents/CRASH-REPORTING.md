# Sentry reporting

## Configuration

- Android core and NDK SDKs are pinned to `8.56.0`.
- Build with `CABIN_SENTRY_DSN`; GitHub Actions already has that repository secret.
- Sentry organization: `eu-virtual`; project: `cabin`.
- Add an organization upload token as the GitHub Actions secret `SENTRY_AUTH_TOKEN`.
  Never put it in an APK or commit it. `sentry.properties`, `.sentryclirc` and `.env`
  files are ignored. The DSN is an ingestion address embedded in the APK.
- Reporting defaults off. Enable **Settings → Logs → Share crash reports** and tap
  **Send test report**. “Queued” means accepted by the SDK, not delivered.

## Coverage

Java/Kotlin crashes, current ANRs and native crashes are enabled after consent.
Handled exceptions passed through Cabin's Logger, updater check/download failures,
and Joying control/start/decoder failures are captured. Cancellation is excluded.
Other caught exceptions that never reach these paths are not automatically captured.

Sentry Logs receives TRACE/DEBUG/INFO/WARN/ERROR diagnostics from the central Logger
and DEBUG diagnostics from DebugJournal. They contain severity and the originating
code location, **not the original free-form message or variable values**. This works
independently of local file-log filters. At most 120 logs and 10 handled exceptions
are accepted per minute; automatic crash capture has a separate SDK path. Hot loops
can consume the log budget. Code compiled out by `logDebugOnly` is unavailable in
release builds. Release call sites can be obfuscated; retain the R8 mapping.

Fixed projection/Joying events and the latest 40 diagnostic breadcrumbs provide
context. The test button also emits a `Cabin event TEST_REPORT` log. SDK internal
DEBUG output goes to local logcat only in debug builds, not to remote raw logs.

## Data boundary

Events are rebuilt before sending. They retain release/environment, Android API,
exception classes, handled/crash mechanism, stack source locations, native addresses,
build IDs and crashed-thread IDs. Cached crashes retain their original release and
mapping ID after an app update. Arbitrary messages, exception text, user/request
fields, custom context, thread names, stack variables and absolute source paths are
removed. Log attributes are replaced with the build release and environment. Raw attachments have a zero-byte
limit and are cleared in the event callback.

Automatic device/network breadcrumbs, screenshots, view hierarchies, raw ANR and
tombstone attachments, historical ANRs/tombstones, session tracking, tracing, metrics
and Java scope persistence are disabled. Native scope sync carries sanitized
breadcrumbs. No Replay dependency is added. No audio, GPS, phone identity, SSID,
media metadata or raw CAN payload is intentionally collected. The HTTPS ingestion
service can observe the connection IP address.

At most 20 envelopes are cached in app-private no-backup storage. Logs use the SDK's
bounded batch processor. Disabling closes Sentry and deletes its cache; already
transmitted or in-flight reports cannot be recalled. Consent is excluded from backups.

## Release symbols

The release workflow generates and embeds `CABIN_SENTRY_MAPPING_UUID`. It retains
that UUID, the R8 mapping and the native debug-symbol ZIP in the CI artifact. The
pinned, checksum-verified Sentry CLI `2.58.4` uploads the mapping using that exact UUID
and uploads native symbols with server-processing checks before publishing.
This CLI version supports the explicit `--uuid` option used by the build.
A missing upload token or a failed upload blocks release publication.

The diagnostics native library is built with FULL symbol metadata. Sentry's own
prebuilt native libraries may contain fewer symbols. Full source-level resolution
of third-party or OEM binaries requires symbols from their vendors.

## Verification status

On 2026-09-15 the supplied DSN accepted a synthetic `TEST_REPORT` with HTTP 200:
`fb9d4da9e65d4f9a8808cc19441b5fb8`, environment `integration-check`.
This verifies the ingestion endpoint, not device delivery or dashboard visibility.

Tests cover private-field removal, native metadata preservation, log filtering,
rate limits, consent and cache deletion. A device test must still verify a Java
crash, an ANR and a native crash after restarting the app, plus logs and resolved
stack traces in the Sentry dashboard. No physical head-unit validation is claimed.

Reference: [pinned SDK source](https://repo.maven.apache.org/maven2/io/sentry/sentry-android-core/8.56.0/sentry-android-core-8.56.0-sources.jar).
