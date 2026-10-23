plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

/**
 * The Linux dev build: the FF4 interface drawn on the desktop, from the same
 * portable core the Android app uses.
 *
 * It renders through a software canvas rather than a GPU, for two reasons: the
 * canvas is what the headless PNG export writes, so what you see on screen is
 * exactly what the tests and I can inspect; and it keeps the build free of third
 * party graphics dependencies while the interface is still moving.
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
    implementation(project(":core"))
    // Reading the engine's JSON replies. The Android app already uses this API
    // (from the platform), so the two sides parse the same shapes the same way.
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.freeenterprise.desktop.MainKt")
    applicationName = "freeenterprise"
}

// The font and the cursor are the app's own assets; copy them in so the desktop
// build has no path assumptions and the two can never disagree about them.
// One `into` per source, inside the `from` block. A task-level `into` would apply
// to every source and flatten the layout, which is how the font first ended up at
// /ff4/ff4.ttf and nowhere to be found.
val ff4Assets = tasks.register<Copy>("copyFf4Assets") {
    from(rootProject.file("app/src/main/assets/fonts/ff4.ttf")) { into("fonts") }
    from(rootProject.file("app/src/main/assets/ff4/pointer.png")) { into("ff4") }
    from(rootProject.file("app/src/main/assets/checksum")) { into("checksum") }
    into(layout.buildDirectory.dir("generated/ff4resources"))
}
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/ff4resources"))
tasks.named("processResources") { dependsOn(ff4Assets) }

// The app version, so the desktop knows what it is running: needed for the status
// line and for deciding whether a release is newer. Taken from the app module, so
// there is one number for both platforms.
val appVersion = providers.provider {
    Regex("versionName = \"([^\"]+)\"").find(
        rootProject.file("app/build.gradle.kts").readText()
    )?.groupValues?.get(1) ?: "0.0.0"
}
val writeVersion = tasks.register("writeVersion") {
    val out = layout.buildDirectory.file("generated/ff4resources/version.txt")
    outputs.file(out)
    val version = appVersion
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText(version.get()) }
    }
}
tasks.named("processResources") { dependsOn(writeVersion) }

// The emulator libraries, built by `make -C desktop/native`. Copied next to the
// application so a distribution can carry them; Main looks here first.
val copyNativeLibs = tasks.register<Copy>("copyNativeLibs") {
    from(layout.projectDirectory.dir("native/build")) {
        include("libretro.so", "libff4emu.so", "libff4input.so")
    }
    into(layout.buildDirectory.dir("native"))
}
tasks.named("classes") { dependsOn(copyNativeLibs) }
