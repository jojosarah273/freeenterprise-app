plugins {
    id("org.jetbrains.kotlin.jvm")
}

/**
 * The portable FF4 core: menu model, draw geometry, palette and metrics.
 *
 * Deliberately has no Android dependency at all, so the mobile app, the desktop
 * client and (later) an iOS client share one implementation of the interface.
 * Anything that needs a platform goes behind an interface in this module and is
 * implemented per platform.
 */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
