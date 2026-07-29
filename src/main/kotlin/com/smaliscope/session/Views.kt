package com.smaliscope.session

/**
 * 推给前端的调试状态视图。所有字段都用「人话」表达：
 * 用户看到的是行、寄存器、类型，不该出现 dex_pc / tag / slot / ClassPrepare 这些词。
 * （dexPc 字段仅用于前端定位高亮行，不直接展示。）
 */

data class RegisterView(
    val reg: Int,
    /** v0/p1 这样的 smali 习惯名。 */
    val name: String,
    /** 推导出的类型，中文。 */
    val type: String,
    val value: String,
    /** 相比上一步是否发生变化——寄存器 diff 高亮的依据。 */
    val changed: Boolean,
    val readable: Boolean,
    /**
     * 「推导出了可读的类型，但设备拒绝读取」时的原因。
     * 非 null 表示我们确实尝试过并失败了——这与「该位置本就没有有效值」是两回事，
     * 统计寄存器可读率时必须分开算。
     */
    val error: String? = null,
    /** 引用型才有，供对象图下钻。 */
    val objectId: Long? = null,
    val expandable: Boolean = false,
    /** 参数寄存器的语义提示，如 this。 */
    val hint: String? = null,
)

data class InsnView(
    val dexPc: Int,
    val index: Int,
    val text: String,
    val opcode: String,
    val reads: List<Int>,
    val writes: List<Int>,
    val isBranch: Boolean,
    val isInvoke: Boolean,
    val isReturn: Boolean,
    /** 中文解释，来自指令词典。 */
    val doc: String?,
)

data class BlockView(
    val id: Int,
    val startPc: Int,
    val endPc: Int,
    val successors: List<Int>,
    /** 是否被执行过——CFG「走过的路」着色。 */
    val visited: Boolean,
    val current: Boolean,
)

data class MethodView(
    val fqcn: String,
    val method: String,
    val signature: String,
    val registerCount: Int,
    /** v0/p1 这样的显示名，按寄存器号排列。前端靠它把指令文本里的寄存器与读写集对上。 */
    val registerNames: List<String>,
    val instructions: List<InsnView>,
    val blocks: List<BlockView>,
    val analysisWarning: String?,
)

data class FrameView(
    val frameId: Long,
    val depth: Int,
    val fqcn: String,
    val method: String,
    val signature: String,
    val dexPc: Int,
    /** 该方法有无静态模型：framework 方法拉不到 dex，只能显示位置。 */
    val hasModel: Boolean,
    val registers: List<RegisterView>,
)

data class BreakpointView(
    val id: Int,
    val fqcn: String,
    val method: String,
    val signature: String,
    val dexPc: Int,
    /** pending（类未加载，已自动兜底） / active / error */
    val state: String,
    val hitCount: Int,
    val note: String? = null,
)

data class StepSnapshot(
    val seq: Int,
    val fqcn: String,
    val method: String,
    val dexPc: Int,
    val registers: List<RegisterView>,
    val stackDepth: Int,
)

data class ObjectNode(
    val objectId: Long,
    val label: String,
    val type: String,
    val fields: List<ObjectField>,
    val arrayLength: Int? = null,
    val truncated: Boolean = false,
)

data class ObjectField(
    val name: String,
    val type: String,
    val value: String,
    val objectId: Long? = null,
    val expandable: Boolean = false,
)

data class DebugState(
    /** connecting | running | suspended | detached */
    val status: String,
    /** 中文状态说明，直接给用户看。 */
    val message: String,
    val threadId: Long? = null,
    val threadName: String? = null,
    val frames: List<FrameView> = emptyList(),
    val currentFrame: Int = 0,
    /** 命中原因：断点命中 / 单步完成 / 已挂起等待调试器 */
    val reason: String? = null,
    val deoptWarning: Boolean = false,
)
