// Top-level build file for Cabin Native Android App

plugins {
    id("com.android.application") version "9.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.3.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        // JBR 21.0.10 can crash in C2 Node::uncast while compiling Robolectric code.
        // Keep host tests on C1 with enough code cache; Android runtime code is unaffected.
        jvmArgs("-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=512m")
    }
}
