plugins {
    kotlin("jvm") version "2.1.21"
    application
}

repositories {
    mavenCentral()
    // smali 自 3.x 起由 Google 维护，只发布在 Google Maven，Central 上没有。
    google()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    implementation("io.github.skylot:jadx-core:1.5.6")
    // jadx-core 只是反编译引擎；读 dex/apk 的能力在这个输入插件里，缺了它类表是空的。
    implementation("io.github.skylot:jadx-dex-input:1.5.6")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.smaliscope.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
