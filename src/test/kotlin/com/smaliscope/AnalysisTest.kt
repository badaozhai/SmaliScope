package com.smaliscope

import com.smaliscope.analysis.RegKind
import com.smaliscope.analysis.jvmSignature
import com.smaliscope.analysis.paramRegisterCount
import com.smaliscope.analysis.typeToFqcn
import com.smaliscope.dict.SmaliDict
import com.smaliscope.jdwp.DataReader
import com.smaliscope.jdwp.DataWriter
import com.smaliscope.jdwp.IdSizes
import com.smaliscope.jdwp.Location
import com.smaliscope.jdwp.Tag
import com.smaliscope.jdwp.TypeTag
import com.smaliscope.server.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 这些是不需要设备就能锁住的部分：签名换算、类型映射、词典匹配、协议编解码。 */
class AnalysisTest {

    @Test
    fun `参数寄存器数：long 与 double 占两个，实例方法额外算 this`() {
        assertEquals(1, paramRegisterCount(emptyList(), isStatic = false))
        assertEquals(0, paramRegisterCount(emptyList(), isStatic = true))
        assertEquals(3, paramRegisterCount(listOf("I", "I"), isStatic = false))
        assertEquals(4, paramRegisterCount(listOf("J", "I"), isStatic = false))
        assertEquals(4, paramRegisterCount(listOf("D", "D"), isStatic = true))
        assertEquals(2, paramRegisterCount(listOf("Ljava/lang/String;"), isStatic = false))
    }

    @Test
    fun `JVM 签名与类名换算`() {
        assertEquals("(II)I", jvmSignature(listOf("I", "I"), "I"))
        assertEquals("()V", jvmSignature(emptyList(), "V"))
        assertEquals(
            "(Ljava/lang/String;[I)Ljava/lang/String;",
            jvmSignature(listOf("Ljava/lang/String;", "[I"), "Ljava/lang/String;"),
        )
        assertEquals("com.foo.Bar", typeToFqcn("Lcom/foo/Bar;"))
        assertEquals("I", typeToFqcn("I"))
    }

    @Test
    fun `寄存器类型到 JDWP tag 的映射`() {
        // 32 位整型族统一按 INT 读，窄语义交给 UI 渲染。
        for (k in listOf(RegKind.BOOLEAN, RegKind.BYTE, RegKind.CHAR, RegKind.SHORT, RegKind.INT)) {
            assertEquals(Tag.INT, k.jdwpTag, "$k 应按 INT 读")
        }
        assertEquals(Tag.LONG, RegKind.LONG_LO.jdwpTag)
        assertEquals(Tag.DOUBLE, RegKind.DOUBLE_LO.jdwpTag)
        assertEquals(Tag.FLOAT, RegKind.FLOAT.jdwpTag)
        assertEquals(Tag.OBJECT, RegKind.REFERENCE.jdwpTag)

        // 字面量 0 的二义性：按 INT 读，避免凭空造出一个对象引用。
        assertEquals(Tag.INT, RegKind.NULL.jdwpTag)

        // 高半部随低半一起读出，不单独取；读不出来的老实标为不可读。
        for (k in listOf(RegKind.LONG_HI, RegKind.DOUBLE_HI,
                         RegKind.UNKNOWN, RegKind.UNINIT, RegKind.CONFLICTED)) {
            assertNull(k.jdwpTag, "$k 不应该被读取")
            assertTrue(!k.readable)
        }
    }

    @Test
    fun `指令词典按最长前缀匹配，变体落到所属指令族`() {
        assertEquals("整数加法", SmaliDict.lookup("add-int")?.cn)
        assertEquals("整数加法", SmaliDict.lookup("add-int/lit8")?.cn)
        assertEquals("整数加法", SmaliDict.lookup("add-int/2addr")?.cn)

        // if-ge 与 if-gez 是两条不同的指令，不能被前缀吃掉。
        assertEquals("前者大于等于后者则跳转", SmaliDict.lookup("if-ge")?.cn)
        assertEquals("如果大于等于 0 就跳转", SmaliDict.lookup("if-gez")?.cn)

        assertNotNull(SmaliDict.lookup("invoke-virtual/range"))
        assertNotNull(SmaliDict.lookup("const-string/jumbo"))
        assertNotNull(SmaliDict.lookup("move-wide/16"))
        assertEquals("空操作，什么也不做", SmaliDict.lookup("nop")?.cn)
        assertNull(SmaliDict.lookup("完全不存在的指令"))

        // move-result 的说明要点出「返回值在下一条」这个新手常见困惑。
        assertTrue(SmaliDict.describe("invoke-virtual")!!.contains("move-result"))
    }

    @Test
    fun `JDWP 数据段读写往返一致`() {
        val ids = IdSizes(fieldId = 8, methodId = 8, objectId = 8, refTypeId = 8, frameId = 8)
        val loc = Location(TypeTag.CLASS, classId = 0x1234_5678L, methodId = 0xABCDL, index = 42L)

        val bytes = DataWriter(ids)
            .writeInt(-7)
            .writeString("中文 smali")
            .writeLocation(loc)
            .writeLong(Long.MIN_VALUE)
            .toByteArray()

        val r = DataReader(bytes, ids)
        assertEquals(-7, r.readInt())
        assertEquals("中文 smali", r.readString())
        assertEquals(loc, r.readLocation())
        assertEquals(Long.MIN_VALUE, r.readLong())
        assertEquals(0, r.remaining)
    }

    @Test
    fun `ID 宽度不是 8 字节时仍然对齐`() {
        val ids = IdSizes(fieldId = 4, methodId = 4, objectId = 4, refTypeId = 4, frameId = 4)
        val bytes = DataWriter(ids).writeObjectId(0xDEADBEEFL).writeInt(1).toByteArray()
        assertEquals(8, bytes.size)
        val r = DataReader(bytes, ids)
        assertEquals(0xDEADBEEFL, r.readObjectId())
        assertEquals(1, r.readInt())
    }

    @Test
    fun `既读又写同一个寄存器的指令，读边不能丢`() {
        // 这三类都是「vA 既是源又是汇」，靠「全部引用寄存器减去写入的」来算读集会把读边吃掉：
        //   add-int/lit8 v0, v0, 1   —— i = i + 1
        //   add-int/2addr v1, v2     —— v1 = v1 + v2
        //   check-cast   p1, LFoo;   —— p1 = (Foo) p1
        // 前两个已在 testapp 上验证，check-cast 是在真实混淆应用上发现的。
        assertTrue(com.android.tools.smali.dexlib2.Opcode.CHECK_CAST.setsRegister(),
            "dexlib2 把 check-cast 标为 SETS_REGISTER，所以必须显式把它的 A 位也算作读")
        assertTrue(com.android.tools.smali.dexlib2.Opcode.ADD_INT_2ADDR.setsRegister())
        assertTrue(com.android.tools.smali.dexlib2.Opcode.ADD_INT_2ADDR.name.endsWith("/2addr"))
        assertTrue(com.android.tools.smali.dexlib2.Opcode.ADD_INT_LIT8.setsRegister())
    }

    @Test
    fun `JSON 转义不会产出非法文本`() {
        assertEquals("\"a\\\"b\"", Json.str("a\"b"))
        assertEquals("\"a\\\\b\"", Json.str("a\\b"))
        assertEquals("\"\\n\"", Json.str("\n"))
        assertEquals("null", Json.str(null))
        // 控制字符要转成 \u 形式，否则 JSON.parse 会失败。
        assertEquals("\"\\u0001\"", Json.str("\u0001"))
        assertEquals("{\"k\":1}", Json.obj("k" to Json.num(1)))
        assertEquals("[1,2]", Json.intArr(listOf(1, 2)))
    }
}
