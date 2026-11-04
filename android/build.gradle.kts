buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kadb 2.1.5 (pairing phone activation) collected Kotlin 2.4the " Built In " AGP 9.4.1 Kotlin 2.2.10 I don't read his metadata.
        // AGP takes KGP from classpath script, so pin here; compose-plugin below the same version.
        // https://developer.android.com/build/releases/agp-9-0-0-release-notes
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    // version = KGP of buildscript above; launcher Headswap Desktop on Compose
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
