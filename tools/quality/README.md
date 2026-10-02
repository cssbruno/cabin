# Android release checks

The `android-quality.yml` workflow tests API 27 (AOSP) and API 35 (Google APIs) on Pixel 2 x86_64 emulators. API 27 has no Google APIs x86_64 image in the SDK catalog. Release publication depends on this workflow passing. `releaseCheck` uses the production release minification rules, is not debuggable, and uses the debug signing key solely for local/CI installation. It must not be published as a production update.

The instrumented variant preserves a short, explicit list of shared AndroidX runner/Kotlin runtime entry points in `app/proguard-release-check.pro`. This prevents the separate test APK from calling helpers removed by whole-program optimization. App classes and Compose internals remain optimized; ordinary production releases do not use these test-only rules. Device tests navigate Android accessibility nodes and public component intents, with no Compose testing hooks linked into the app.

Build the instrumented variant separately from debug unit tests:

```sh
./gradlew :app:testDebugUnitTest :diagnostics:testDebugUnitTest --no-configuration-cache
./gradlew :app:assembleReleaseCheck :app:assembleReleaseCheckAndroidTest -Pcabin.testBuildType=releaseCheck --no-configuration-cache
python3 tools/quality/device_checks.py --apk app/build/outputs/apk/releaseCheck/app-releaseCheck.apk --test-apk app/build/outputs/apk/androidTest/releaseCheck/app-releaseCheck-androidTest.apk --output app/build/reports/device-quality
python3 tools/quality/startup_benchmark.py --apk app/build/outputs/apk/releaseCheck/app-releaseCheck.apk --output app/build/reports/device-quality/startup.json
```

Run `device_checks.py` only against a disposable emulator or test device: it clears Cabin's app data to verify first launch. Use `--serial` to select the emulator when more than one device is connected. The wrapper checks the instrumentation result rather than trusting the shell exit code, and saves the APK hashes, device identity, full test output and resource samples before any cleanup. CI uses this same wrapper; missing evidence fails the run.

`ReleaseSmokeTest` launches the actual minified activity with microphone/location/notification access denied, opens settings, searches a real log, prepares a frozen export and saves it through Android's document picker. The second test uses five warmup cycles and thirty measured cycles of no-adapter connection attempts/stops, driver changes, settings navigation and activity recreation. It compares the first and last five measurements, allowing growth of eight threads, twelve file descriptors and 24 MiB of retained Java heap. Every sample is retained in `quality/soak.json`. This does not measure a live USB/video session or native allocator retention; physical adapter soak testing remains required.

The startup script reinstalls the supplied APK and takes seven cold and seven warm measurements. Cold means force-stop followed by `am start -W`; warm means return from Home to the same activity. It records the APK SHA-256, emulator model/build fingerprint, Android timings, medians and fixed-label application startup phases. These measurements describe Android launch completion, not phone projection readiness. The JSON budget file is an explicit CI ceiling; baseline and budget calibration evidence belong in the delivery report.

The initial local reference (API 36, `sdk_gphone64_x86_64`, Pixel 2 profile, KVM, two cores, 2048 MiB RAM, host graphics) measured a 440 ms cold median and 28 ms warm median. Activity creation to first draw was the largest measured phase at 239 ms. The 2500 ms cold / 500 ms warm median ceilings allow for slower shared CI hosts while rejecting major regressions. Preserve the raw samples and final APK hash with the delivery evidence; recalibrate deliberately when changing the emulator image or runner rather than increasing budgets to hide a failure.

Emulator artifacts contain test logs and synthetic records only. No production account, vehicle or phone data is required. Local emulator graphics may need `-gpu host -feature -Vulkan` if the installed SwiftShader library crashes.
