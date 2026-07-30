package com.smaliscope

import com.smaliscope.analysis.ApkIndex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * 预设断点模板的静态识别（ROADMAP 零散项）。用自带的测试 APK 验证：
 * MainActivity 继承 android.app.Activity 且声明了 onCreate(Bundle)，应被识别为 Activity 入口。
 */
class TemplateTest {

    private val apk = File("testapp/build/smaliscope-test.apk")

    @Test
    fun `识别出 Activity 子类的 onCreate`() {
        if (!apk.isFile) {
            println("跳过：未构建 testapp（先跑 ./testapp/build.sh）")
            return
        }
        val index = ApkIndex(apk, api = 34)
        val acts = index.activityOnCreates()
        assertTrue(
            acts.any { it.fqcn.endsWith(".MainActivity") && it.name == "onCreate" },
            "应识别出 MainActivity.onCreate，实际：$acts",
        )
        // onCreate 的签名必须带 Bundle，且解析得到的方法有 dex 代码（能下断点）
        val main = acts.first { it.fqcn.endsWith(".MainActivity") }
        assertEquals("(Landroid/os/Bundle;)V", main.signature)
        assertTrue(index.model(main)?.instructions?.isNotEmpty() == true, "onCreate 应有方法体")
    }

    @Test
    fun `非 Activity 类不会被误报`() {
        if (!apk.isFile) return
        val index = ApkIndex(apk, api = 34)
        val acts = index.activityOnCreates()
        // Calc 是普通类，不是 Activity，不该出现
        assertTrue(acts.none { it.fqcn.endsWith(".Calc") }, "Calc 不是 Activity，不该被识别为入口")
    }
}
