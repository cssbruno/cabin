plugins { id("com.android.application") }
android {
 namespace = "com.cabin.hondacustom"
 compileSdk = 36
 defaultConfig { applicationId = "com.cabin.hondacustom"; minSdk = 17; targetSdk = 28; versionCode = 2; versionName = "2.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
 // This APK is sideloaded onto legacy OEM firmware, not published to Google Play.
 lint { disable += "ExpiredTargetSdkVersion" }
 testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.robolectric:robolectric:4.14.1") }
