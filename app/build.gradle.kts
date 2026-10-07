import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds pass -PappVersion=X.Y.Z from the git tag (see .github/workflows/build.yml).
// Any other build is a dev build of the last v* tag: N commits after vX.Y.Z becomes "X.Y.Z-dev.N", whose
// versionCode sits above that release and below the next one, so it installs over the release it came from.
val describe = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--long", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--exclude", "*-*")
    }.standardOutput.asText.get().trim()
}
val describeOutput = describe.getOrNull()
// No git or no release tag falls back to 0.0.1; a tag describe found but this can't parse is an error, not a silent downgrade.
val describedTag = describeOutput?.let {
    Regex("""v(\d+\.\d+\.\d+)-(\d+)-g\p{XDigit}+""").matchEntire(it)
        ?: throw GradleException("Unexpected git describe output: $it (delete the malformed v* tag)")
}?.groupValues
val taggedVersion = findProperty("appVersion") as String?
// Warned, not silent: a 0.0.1 build can't be installed over a release (adb install -r refuses the downgrade).
if (taggedVersion == null && describeOutput == null) {
    logger.warn("git describe found no v* tag (${describe.exceptionOrNull()?.message}): building as 0.0.1.")
}
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

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the merged debug manifest and resources (HOME activity, themes, versionName).
        unitTests.isIncludeAndroidResources = true
    }

    // Files nothing reads at runtime: kotlinx-coroutines' hook for the coroutines debug agent (never installed on a
    // phone), the Kotlin builtins and tooling metadata (only kotlin-reflect, which the app doesn't use, reads them), and
    // the libraries' license texts.
    packaging {
        resources.excludes += setOf("DebugProbesKt.bin", "kotlin/**.kotlin_builtins", "kotlin-tooling-metadata.json", "META-INF/**/LICENSE.txt")
    }
}

androidComponents {
    // The libraries' version stamps go only from release builds: Android Studio's Layout Inspector reads them on debug ones.
    onVariants(selector().withBuildType("release")) { it.packaging.resources.excludes.add("META-INF/*.version") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

composeCompiler {
    // Types the compiler can't prove immutable but this app never mutates (see the file for why each is safe), so
    // composables taking them skip when an equal value comes in, not only the same instance.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
    // -PcascadeComposeReports also writes the compiler's stability reports (*-classes.txt, *-composables.txt) and
    // metrics to build/compose_compiler; other builds don't.
    if (providers.gradleProperty("cascadeComposeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
        // The reports aren't a compile input, so an up-to-date compile would write none: always rerun it.
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { outputs.upToDateWhen { false } }
    }
}

// JVM screenshot tests (src/test/.../screenshots, see SCREENSHOTS.md) only run with -PcascadeScreenshots,
// which also limits the run to them; ordinary unit test runs skip them.
val screenshots = providers.gradleProperty("cascadeScreenshots").isPresent
// Gradle's --tests (also what an IDE gutter run passes) narrows a run, like -PcascadeScreenshotFilter does.
val testsNarrowed = gradle.startParameter.taskRequests.any { request -> request.args.any { it.startsWith("--tests") } }
tasks.withType<Test>().configureEach {
    // Robolectric on the JDK 21 runtime reaches into FileDescriptor internals and loads its native graphics library.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    if (!screenshots) exclude("com/gh00ul/cascade/screenshots/**")
    else {
        filter.includeTestsMatching("com.gh00ul.cascade.screenshots.*")
        val dir = layout.buildDirectory.dir("screenshots").get().asFile
        val shotFilter = providers.gradleProperty("cascadeScreenshotFilter").getOrElse("")
        systemProperty("cascade.screenshots.dir", dir.path)
        systemProperty("cascade.screenshots.filter", shotFilter)
        outputs.upToDateWhen { false }
        // A full run starts from an empty folder, so renamed or removed shots don't linger. A run narrowed by
        // -PcascadeScreenshotFilter or --tests keeps the other PNGs.
        if (shotFilter.isEmpty() && !testsNarrowed) doFirst { dir.listFiles { f -> f.extension == "png" }?.forEach { it.delete() } }
        doLast { println("Screenshots: ${dir.path} (${dir.listFiles { f -> f.extension == "png" }?.size ?: 0} PNGs)") }
    }
}

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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
