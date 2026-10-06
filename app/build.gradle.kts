import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds pass -PappVersion=X.Y.Z from the git tag (see .github/workflows/build.yml).
// Any other build is a dev build of the last v* tag: N commits after vX.Y.Z becomes "X.Y.Z-dev.N", whose
// versionCode sits above that release and below the next one, so it installs over the release it came from.
val describeOutput = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--long", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--exclude", "*-*")
    }.standardOutput.asText.get().trim()
}.getOrNull()
// No git or no release tag falls back to 0.0.1; a tag describe found but this can't parse is an error, not a silent downgrade.
val describedTag = describeOutput?.let {
    Regex("""v(\d+\.\d+\.\d+)-(\d+)-g\p{XDigit}+""").matchEntire(it)
        ?: throw GradleException("Unexpected git describe output: $it (delete the malformed v* tag)")
}?.groupValues
val taggedVersion = findProperty("appVersion") as String?
val appVersion = taggedVersion ?: describedTag?.get(1) ?: "0.0.1"
val devBuild = if (taggedVersion != null) 0 else describedTag?.get(2)?.toInt() ?: 0

// Every release must be signed with the same key or Android refuses to install it as an update.
// The key lives in .signing/ (not in git): CI decodes it from secrets, locally keystore.properties holds the password.
val signingDir = rootProject.file(".signing")
val releaseKeystore = signingDir.resolve("release.jks")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
    ?: signingDir.resolve("keystore.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }.getProperty("password") }
        ?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.gh00ul.cascade"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gh00ul.cascade"
        minSdk = 26
        targetSdk = 36
        versionName = if (devBuild == 0) appVersion else "$appVersion-dev.$devBuild"
        // v0.2.0 is 200000, its dev builds 200001..200999 (capped), v0.2.1 is 201000.
        versionCode = appVersion.split(".").map(String::toInt)
            .let { (major, minor, patch) -> (major * 10000 + minor * 100 + patch) * 1000 + devBuild.coerceAtMost(999) }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = keystorePassword
            keyAlias = "cascade"
            keyPassword = keystorePassword
        }
    }

    buildFeatures { compose = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key (e.g. a fresh clone), fall back to the debug key so the build still installs.
            // With the key but no password, keep the release config so the build fails instead of debug-signing.
            if (!releaseKeystore.exists()) {
                logger.warn("No .signing/release.jks: release builds use the debug key and can't update published installs.")
            } else if (keystorePassword == null) {
                logger.warn("No password for .signing/release.jks (KEYSTORE_PASSWORD or keystore.properties): release builds will fail.")
            }
            signingConfig = signingConfigs.getByName(if (releaseKeystore.exists()) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
