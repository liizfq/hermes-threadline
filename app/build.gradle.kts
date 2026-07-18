plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

/**
 * Release-signing credentials: looks first at environment variables, then at a
 * git-ignored `keystore.properties` file at the project root. Returns null for
 * any entry that is missing or blank. No credentials are ever read from — or
 * written into — the repository; if any value is absent, the build falls back
 * to producing an unsigned release APK (see android { ... } below).
 */
data class ReleaseSigningCredentials(
    val storeFile: java.io.File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/**
 * Reads a simple Java .properties file. Only handles flat key=value entries;
 * does not interpret escapes or multi-line values. Suitable for the local,
 * user-maintained keystore.properties file.
 */
fun loadProperties(file: java.io.File): Map<String, String> {
    val props = mutableMapOf<String, String>()
    file.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (key.isNotEmpty()) props[key] = value
            }
        }
    }
    return props
}

fun resolveReleaseSigningCredentials(rootProject: org.gradle.api.Project): ReleaseSigningCredentials? {
    val propsFile = rootProject.file("keystore.properties")
    val props = if (propsFile.exists()) loadProperties(propsFile) else emptyMap()

    fun credential(envKey: String): String? {
        val envVal = System.getenv(envKey)
        if (!envVal.isNullOrBlank()) return envVal
        val propVal = props[envKey]
        if (!propVal.isNullOrBlank()) return propVal
        return null
    }

    val storeFile = credential("RELEASE_STORE_FILE") ?: return null
    val storePassword = credential("RELEASE_STORE_PASSWORD") ?: return null
    val keyAlias = credential("RELEASE_KEY_ALIAS") ?: return null
    val keyPassword = credential("RELEASE_KEY_PASSWORD") ?: return null

    val storeRef = rootProject.file(storeFile)
    if (!storeRef.exists()) {
        println("[release-signing] RELEASE_STORE_FILE '$storeFile' does not exist; skipping release signing.")
        return null
    }
    return ReleaseSigningCredentials(storeRef, storePassword, keyAlias, keyPassword)
}

android {
    namespace = "com.hermes.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hermes.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.3.4"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Optional local release signing: credentials are looked up from
            // environment variables or a git-ignored keystore.properties file.
            // When all four are present and the keystore file exists, a signing
            // config is created and applied. When any are missing, the build
            // proceeds normally and produces an unsigned APK.
            resolveReleaseSigningCredentials(rootProject)?.let { creds ->
                signingConfig = signingConfigs.create("userReleaseSigning") {
                    storeFile = creds.storeFile
                    storePassword = creds.storePassword
                    keyAlias = creds.keyAlias
                    keyPassword = creds.keyPassword
                }
                println("[release-signing] Configured local release signing (key alias: ${creds.keyAlias})")
            } ?: println("[release-signing] No release signing credentials found; build will produce an unsigned APK.")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // matrix-rust-sdk Android bindings
    implementation(project(":sdk-local"))

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ElementX wysiwyg (HTML rendering with table/list support)
    implementation("io.element.android:wysiwyg:2.42.0")
    implementation("io.element.android:wysiwyg-compose:2.42.0")
    implementation("org.jsoup:jsoup:1.17.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // image loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("me.saket.telephoto:zoomable-image-coil:0.13.0")

    // media playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // networking (ntfy push)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // UnifiedPush connector library (handles AND_3, including setShareIdentityEnabled for SDK 34+)
    implementation("org.unifiedpush.android:connector:3.0.10")

    // WorkManager — process-independent event queue worker
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.test:core:1.6.1")

    // Leak detection (debug only)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
