plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.anthropic.java)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // The live check (LIVE_SMOKE=1) prints what each real feed returned and must never be cached.
    if (System.getenv("LIVE_SMOKE") != null) {
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
