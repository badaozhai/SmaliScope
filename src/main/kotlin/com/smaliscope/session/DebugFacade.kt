package com.smaliscope.session

/**
 * 调试器门面接口：MCP 的工具集只依赖这个，不直接依赖具体实现。
 *
 * 有两个实现：
 * - [Debugger]：进程内自己持有 JDWP 会话（`smaliscope mcp` 单独跑时用）。
 * - [RemoteDebugger]：把每个调用转成对本机工作台的 HTTP 请求。
 *   于是 agent 在终端里下的断点、读的寄存器，和用户在 Web 界面上看到的是
 *   **同一个活会话**——而不是各开一个平行的空调试器。
 *
 * 只收录 MCP 工具集真正用到的那些方法。`Debugger` 上其它 Web 专用的东西
 * （startAsync / SSE 回调 / LLM 配置读写等）不进这个接口。
 */
interface DebugFacade {

    val state: DebugState
    val pkg: String?

    fun bootstrap(want: String? = null): Debugger.Bootstrap
    fun loadApp(packageName: String): Int

    fun classNames(filter: String? = null, limit: Int = 500): List<String>
    fun methodsOf(fqcn: String): List<Triple<String, String, Int>>
    fun methodView(fqcn: String, method: String, signature: String, pc: Int?): MethodView?
    fun resolveClass(name: String): String?
    fun resolveMethod(fqcn: String, method: String, signature: String?): String?
    fun javaSource(fqcn: String): Pair<String?, String?>

    fun addBreakpoint(
        fqcn: String, method: String, signature: String, dexPc: Int,
        condition: BpCondition? = null,
    ): BreakpointView
    fun setBreakpointCondition(id: Int, condition: BpCondition?): Boolean
    fun removeBreakpoint(id: Int)
    fun breakpoints(): List<BreakpointView>
    fun breakpointTemplates(): List<Debugger.BpTemplate>
    fun applyTemplate(id: String): List<BreakpointView>

    fun start()
    fun control(action: String)

    /**
     * 发起一个动作，然后等它停下来。MCP 侧没有事件流，一次请求就该拿到结果。
     * 远端实现里，这就是「POST 触发 + 轮询 /api/state 直到 stopSeq 变化」。
     */
    fun actAndWait(timeoutMs: Long, action: () -> Unit): DebugState?

    fun readFrame(depth: Int): FrameView?
    fun writeRegister(depth: Int, reg: Int, text: String): FrameView
    fun expandObject(objectId: Long): ObjectNode?

    fun llmEnabled(): Boolean
    fun explain(fqcn: String, method: String, signature: String, dexPc: Int?): String
    fun nameRegisters(fqcn: String, method: String, signature: String): String
}
