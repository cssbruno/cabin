plugins { id("com.android.application") }
android {
 namespace = "com.cabin.hondacustom"
 compileSdk = 36
 defaultConfig {
  applicationId = "com.cabin.hondacustom"; minSdk = 17; targetSdk = 28; versionCode = 6; versionName = "3.1.0"
  testInstrumentationRunner = "com.cabin.hondacustom.SmokeInstrumentation"
 }
 buildTypes.getByName("release") {
  // Keep the existing development certificate so 2.0 users can update in place.
  // This is a hardware-unverified prerelease, not a production Honda signer.
  signingConfig = signingConfigs.getByName("debug")
  isDebuggable = false
  isMinifyEnabled = false
 }
 testBuildType = "release"
 // The original Honda implementation remains reference-only and is never packaged.
 sourceSets {
  getByName("main") { java.setSrcDirs(listOf("src/fyt/java")); manifest.srcFile("src/fyt/AndroidManifest.xml"); assets.setSrcDirs(emptyList<String>()) }
  getByName("test") { java.setSrcDirs(listOf("src/fytTest/java")) }
  getByName("androidTest") { java.setSrcDirs(listOf("src/fytAndroidTest/java")); assets.setSrcDirs(listOf("src/fytAndroidTest/assets")) }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
 // This APK is sideloaded onto legacy OEM firmware, not published to Google Play.
 lint { disable += "ExpiredTargetSdkVersion" }
 testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.robolectric:robolectric:4.14.1") }
