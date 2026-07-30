package com.smaliscope.dict

/**
 * smali 指令中文词典。悬停解释与「读→写」提示都从这里取。
 *
 * 查找顺序：先精确匹配整条指令名，再按前缀族匹配（如 add-int/lit8 落到 add-int 族），
 * 这样不必为每个变体各写一条。
 *
 * 覆盖率：`DictCoverageTest` 以 dexlib2 的 `Opcode` 枚举为权威全集，保证每一条会出现在
 * 普通 APK dex 里的指令（即非 odex-only、非 payload）都能查到解释，共 224 条。
 * 条目按 dex 指令集逐条编写并抽样核对，不走运行期网络——全部编译进包，零依赖零请求。
 */
object SmaliDict {

    data class Entry(val cn: String, val note: String? = null)

    private val exact: Map<String, Entry> = mapOf(
        "nop" to Entry("空操作，什么也不做", "常见于对齐填充，可以直接跳过"),
        "move-result" to Entry("把上一条 invoke 的返回值取回到寄存器",
            "新手常见困惑：invoke 指令本身不带返回值，必须看紧跟其后的 move-result*"),
        "move-result-wide" to Entry("取回上一条调用的 long/double 返回值"),
        "move-result-object" to Entry("取回上一条调用的对象返回值"),
        "move-exception" to Entry("把捕获到的异常对象存入寄存器", "只出现在 catch 块的第一条"),
        "return-void" to Entry("方法结束，不返回值"),
        "return" to Entry("返回一个 32 位值（int/float/boolean 等）"),
        "return-wide" to Entry("返回一个 64 位值（long/double）"),
        "return-object" to Entry("返回一个对象引用"),
        "throw" to Entry("抛出寄存器里的异常对象"),
        "goto" to Entry("无条件跳转", "执行会直接跳到目标行，不会走到下一行"),
        "check-cast" to Entry("类型强转检查，失败则抛 ClassCastException"),
        "instance-of" to Entry("判断对象是否为某类型，结果 0/1 存入寄存器"),
        "array-length" to Entry("取数组长度存入寄存器"),
        "new-instance" to Entry("创建对象但尚未构造",
            "此时对象字段都是默认值，要等紧随其后的 invoke-direct <init> 执行完才算构造好"),
        "new-array" to Entry("创建指定长度的数组"),
        "filled-new-array" to Entry("用给定寄存器的值直接构造一个数组"),
        "fill-array-data" to Entry("用常量表批量填充数组"),
        "monitor-enter" to Entry("进入同步块，加锁"),
        "monitor-exit" to Entry("离开同步块，解锁"),
        "packed-switch" to Entry("按连续取值跳转的 switch"),
        "sparse-switch" to Entry("按离散取值跳转的 switch"),
        // ART 优化后才出现的形式，正常反编译 APK 少见，列出以求完整：
        "return-void-no-barrier" to Entry("方法结束不返回值（ART 优化形式，省去构造尾的内存屏障）"),
        "invoke-object-init" to Entry("调用 Object 的空构造函数（ART 对 <init> 的优化形式）"),
    )

    /** 指令族前缀 → 解释。按前缀长度从长到短匹配。 */
    private val families: List<Pair<String, Entry>> = listOf(
        "move-object" to Entry("把一个对象引用从一个寄存器复制到另一个"),
        "move-wide" to Entry("复制一个 64 位值（long/double），占用相邻两个寄存器"),
        "move" to Entry("把一个 32 位值从一个寄存器复制到另一个"),
        "const-string" to Entry("把一个字符串常量放进寄存器"),
        "const-class" to Entry("把一个类对象放进寄存器"),
        "const-wide" to Entry("把一个 64 位常量放进寄存器（占相邻两个）"),
        "const-method-handle" to Entry("把一个 MethodHandle 常量放进寄存器", "invoke-custom/lambda 相关"),
        "const-method-type" to Entry("把一个 MethodType 常量放进寄存器", "invoke-custom/lambda 相关"),
        "const" to Entry("把一个常量放进寄存器", "最直观的一条：执行后能立刻看到该寄存器的值变成常量"),

        "invoke-virtual" to Entry("调用虚方法，按对象的实际类型分派",
            "返回值不在这条指令里，看下一条 move-result*"),
        "invoke-super" to Entry("调用父类实现的方法"),
        "invoke-direct" to Entry("调用非虚方法：构造函数或 private 方法",
            "紧跟 new-instance 的 invoke-direct <init> 就是在执行构造函数"),
        "invoke-static" to Entry("调用静态方法，没有 this 参数"),
        "invoke-interface" to Entry("通过接口调用方法"),
        "invoke-custom" to Entry("invokedynamic，通常来自 lambda 或字符串拼接"),
        "invoke-polymorphic" to Entry("MethodHandle 的多态调用"),

        "iget" to Entry("读实例字段：把对象的某个字段值取到寄存器"),
        "iput" to Entry("写实例字段：把寄存器的值写进对象的某个字段"),
        "sget" to Entry("读静态字段"),
        "sput" to Entry("写静态字段"),
        "aget" to Entry("读数组元素：arr[i] 取到寄存器"),
        "aput" to Entry("写数组元素：把寄存器的值写进 arr[i]"),

        "if-eqz" to Entry("如果寄存器等于 0 就跳转", "对象类型时相当于判断是否为 null"),
        "if-nez" to Entry("如果寄存器不等于 0 就跳转", "对象类型时相当于判断是否非 null"),
        "if-ltz" to Entry("如果小于 0 就跳转"),
        "if-gez" to Entry("如果大于等于 0 就跳转"),
        "if-gtz" to Entry("如果大于 0 就跳转"),
        "if-lez" to Entry("如果小于等于 0 就跳转"),
        "if-eq" to Entry("两个寄存器相等则跳转"),
        "if-ne" to Entry("两个寄存器不等则跳转"),
        "if-lt" to Entry("前者小于后者则跳转"),
        "if-ge" to Entry("前者大于等于后者则跳转", "for 循环的边界判断常编译成它"),
        "if-gt" to Entry("前者大于后者则跳转"),
        "if-le" to Entry("前者小于等于后者则跳转"),

        "add-int" to Entry("整数加法"),
        "sub-int" to Entry("整数减法"),
        "mul-int" to Entry("整数乘法"),
        "div-int" to Entry("整数除法"),
        "rem-int" to Entry("整数取余"),
        "and-int" to Entry("整数按位与"),
        "or-int" to Entry("整数按位或"),
        "xor-int" to Entry("整数按位异或"),
        "shl-int" to Entry("整数左移"),
        "shr-int" to Entry("整数算术右移"),
        "ushr-int" to Entry("整数逻辑右移"),
        "neg-int" to Entry("整数取负"),
        "not-int" to Entry("整数按位取反"),

        // rsub 是「反向减」：结果 = 立即数 − 寄存器，专门给 a = C - x 这类式子
        "rsub-int" to Entry("整数反向减：结果 = 立即数 − 寄存器", "对应源码里的 常量 - 变量"),

        "add-long" to Entry("长整数加法"), "sub-long" to Entry("长整数减法"),
        "mul-long" to Entry("长整数乘法"), "div-long" to Entry("长整数除法"),
        "rem-long" to Entry("长整数取余"),
        "and-long" to Entry("长整数按位与"), "or-long" to Entry("长整数按位或"),
        "xor-long" to Entry("长整数按位异或"),
        "shl-long" to Entry("长整数左移"), "shr-long" to Entry("长整数算术右移"),
        "ushr-long" to Entry("长整数逻辑右移"),
        "neg-long" to Entry("长整数取负"), "not-long" to Entry("长整数按位取反"),

        "add-float" to Entry("单精度浮点加法"), "sub-float" to Entry("单精度浮点减法"),
        "mul-float" to Entry("单精度浮点乘法"), "div-float" to Entry("单精度浮点除法"),
        "rem-float" to Entry("单精度浮点取余"), "neg-float" to Entry("单精度浮点取负"),
        "add-double" to Entry("双精度浮点加法"), "sub-double" to Entry("双精度浮点减法"),
        "mul-double" to Entry("双精度浮点乘法"), "div-double" to Entry("双精度浮点除法"),
        "rem-double" to Entry("双精度浮点取余"), "neg-double" to Entry("双精度浮点取负"),

        "int-to-long" to Entry("int 转 long"), "int-to-float" to Entry("int 转 float"),
        "int-to-double" to Entry("int 转 double"), "int-to-byte" to Entry("int 截断为 byte"),
        "int-to-char" to Entry("int 转 char"), "int-to-short" to Entry("int 截断为 short"),
        "long-to-int" to Entry("long 截断为 int"), "long-to-float" to Entry("long 转 float"),
        "long-to-double" to Entry("long 转 double"),
        "float-to-int" to Entry("float 转 int（截断小数）"), "float-to-long" to Entry("float 转 long（截断小数）"),
        "float-to-double" to Entry("float 转 double"),
        "double-to-int" to Entry("double 转 int（截断小数）"), "double-to-long" to Entry("double 转 long（截断小数）"),
        "double-to-float" to Entry("double 转 float（可能丢精度）"),

        "cmpl-float" to Entry("比较两个 float，NaN 时返回 -1"),
        "cmpg-float" to Entry("比较两个 float，NaN 时返回 1"),
        "cmpl-double" to Entry("比较两个 double，NaN 时返回 -1"),
        "cmpg-double" to Entry("比较两个 double，NaN 时返回 1"),
        "cmp-long" to Entry("比较两个 long，结果为 -1/0/1"),
    )

    // 前缀匹配的候选：指令族 + 精确条目一起纳入，都按长度从长到短排。
    // 把精确条目也当前缀，是为了让 goto/16、filled-new-array/range 这类
    // 「精确基名 + 变体」也能落到基名的解释上——否则它们查不到。
    // dex 的变体后缀有两种分隔符：`/`（/2addr、/lit8、/range、/16、/high16…）
    // 和 `-`（字段/数组访问的类型变体，如 iget-object、aget-wide）。两种都要认。
    // 靠「长前缀优先」避免误伤：const-method-handle 排在 const 前面，先被命中。
    private val prefixIndex: List<Pair<String, Entry>> =
        (families + exact.toList()).sortedByDescending { it.first.length }

    fun lookup(opcodeName: String): Entry? {
        exact[opcodeName]?.let { return it }
        return prefixIndex.firstOrNull { (prefix, _) ->
            opcodeName == prefix ||
                opcodeName.startsWith("$prefix/") ||
                opcodeName.startsWith("$prefix-")
        }?.second
    }

    /** 供 UI 悬浮显示的一行文本。 */
    fun describe(opcodeName: String): String? {
        val e = lookup(opcodeName) ?: return null
        return if (e.note != null) "${e.cn}。${e.note}" else e.cn
    }

    val size: Int get() = exact.size + families.size
}
