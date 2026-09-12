plugins { id("com.android.application") }

android {
    namespace = "com.cabin.hardware"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cabin.hardware.lab"
        minSdk = 27
        targetSdk = 36
        versionCode = 4
        versionName = "0.4-binder-fixes"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
