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

// ── 打包成自带 JRE 的桌面安装包 ────────────────────────────────────────────
// 面向新手却要求先装 JDK 和 Gradle 是自相矛盾的，所以要有这一步。
// 用 JDK 自带的 jpackage，不引第三方打包插件。
//
//   ./gradlew packageApp     → build/jpackage/ 下生成当前平台的安装包
//
// jpackage 只能出「当前平台」的包：mac 上出 .dmg、Windows 上出 .msi、Linux 上出 .deb。
// 三平台齐活需要在三个系统上各跑一次（通常交给 CI 的 matrix）。
// macOS 的 .dmg / .pkg 要求版本号首位 >= 1（CFBundleVersion 的限制），
// 所以打包版本与项目自身的 0.1.0 不一致，这里单独给一个合法值。
val packageVersion = "1.0.0"

val packageApp by tasks.registering(Exec::class) {
    group = "distribution"
    description = "用 jpackage 打出自带 JRE 的本平台安装包"
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/smaliscope").get().asFile
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile

    val os = System.getProperty("os.name").lowercase()
    val type = when {
        os.contains("mac") -> "dmg"
        os.contains("win") -> "msi"
        else -> "deb"
    }

    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
        // installDist 把主 jar 和依赖都放在 lib/ 下，正好是 jpackage 要的 --input。
        val mainJar = installDir.resolve("lib").resolve("${project.name}.jar")
        if (!mainJar.isFile) error("未找到主 jar ${mainJar.name}，先跑 ./gradlew installDist")

        val jpackage = File(System.getProperty("java.home"), "bin/jpackage").absolutePath
        commandLine(
            jpackage,
            "--type", type,
            "--name", "SmaliScope",
            "--app-version", packageVersion,
            "--vendor", "SmaliScope",
            "--description", "面向新手的 DEX/smali 指令级断点调试器",
            "--input", installDir.resolve("lib").absolutePath,
            "--main-jar", mainJar.name,
            "--main-class", "com.smaliscope.MainKt",
            "--dest", outDir.absolutePath,
            // 双击启动就直接开工作台并打开浏览器；命令行用法仍可直接调 .app 内的可执行文件。
            "--arguments", "serve",
            "--java-options", "-Xmx2g",
            "--java-options", "-Dfile.encoding=UTF-8",
        )
    }

    doLast {
        logger.lifecycle("安装包已生成于 ${outDir.absolutePath}（类型 $type）")
    }
}
