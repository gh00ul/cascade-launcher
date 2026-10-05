import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds pass -PappVersion=X.Y.Z from the git tag (see .github/workflows/build.yml).
val appVersion = (findProperty("appVersion") as String?) ?: "0.0.1"

// Every release must be signed with the same key or Android refuses to install it as an update.
// The key lives in .signing/ (not in git): CI decodes it from secrets, locally keystore.properties holds the password.
val signingDir = rootProject.file(".signing")
val releaseKeystore = signingDir.resolve("release.jks")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
    ?: signingDir.resolve("keystore.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty("password") }

android {
    namespace = "com.gh00ul.cascade"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gh00ul.cascade"
        minSdk = 26
        targetSdk = 36
        versionName = appVersion
        versionCode = appVersion.split(".").map(String::toInt)
            .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
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
            signingConfig = signingConfigs.getByName(
                if (releaseKeystore.exists() && keystorePassword != null) "release" else "debug",
            )
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
