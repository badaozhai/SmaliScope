package com.smaliscope.server

import com.smaliscope.session.BlockView
import com.smaliscope.session.BreakpointView
import com.smaliscope.session.DebugState
import com.smaliscope.session.FrameView
import com.smaliscope.session.InsnView
import com.smaliscope.session.MethodView
import com.smaliscope.session.ObjectNode
import com.smaliscope.session.RegisterView
import com.smaliscope.session.StepSnapshot

/**
 * 极简 JSON 生成。只需要「写」——前端发来的命令走查询参数，不需要解析器，
 * 因此不引入序列化库，少一个依赖少一层版本纠纷。
 */
object Json {

    fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    fun str(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""
    fun num(n: Number?): String = n?.toString() ?: "null"
    fun bool(b: Boolean): String = if (b) "true" else "false"

    fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(",", "{", "}") { "${str(it.first)}:${it.second}" }

    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    fun strArr(items: List<String>): String = arr(items.map { str(it) })
    fun intArr(items: List<Int>): String = arr(items.map { it.toString() })

    // ── 视图对象的编码 ──────────────────────────────────────────────────────

    fun of(r: RegisterView): String = obj(
        "reg" to num(r.reg),
        "name" to str(r.name),
        "type" to str(r.type),
        "value" to str(r.value),
        "changed" to bool(r.changed),
        "readable" to bool(r.readable),
        "error" to str(r.error),
        "objectId" to num(r.objectId),
        "expandable" to bool(r.expandable),
        "hint" to str(r.hint),
    )

    fun of(i: InsnView): String = obj(
        "dexPc" to num(i.dexPc),
        "index" to num(i.index),
        "text" to str(i.text),
        "opcode" to str(i.opcode),
        "reads" to intArr(i.reads),
        "writes" to intArr(i.writes),
        "isBranch" to bool(i.isBranch),
        "isInvoke" to bool(i.isInvoke),
        "isReturn" to bool(i.isReturn),
        "doc" to str(i.doc),
    )

    fun of(b: BlockView): String = obj(
        "id" to num(b.id),
        "startPc" to num(b.startPc),
        "endPc" to num(b.endPc),
        "successors" to intArr(b.successors),
        "visited" to bool(b.visited),
        "current" to bool(b.current),
    )

    fun of(m: MethodView): String = obj(
        "fqcn" to str(m.fqcn),
        "method" to str(m.method),
        "signature" to str(m.signature),
        "registerCount" to num(m.registerCount),
        "registerNames" to strArr(m.registerNames),
        "analysisWarning" to str(m.analysisWarning),
        "instructions" to arr(m.instructions.map { of(it) }),
        "blocks" to arr(m.blocks.map { of(it) }),
    )

    fun of(f: FrameView): String = obj(
        "frameId" to num(f.frameId),
        "depth" to num(f.depth),
        "fqcn" to str(f.fqcn),
        "method" to str(f.method),
        "signature" to str(f.signature),
        "dexPc" to num(f.dexPc),
        "hasModel" to bool(f.hasModel),
        "registers" to arr(f.registers.map { of(it) }),
    )

    fun of(b: BreakpointView): String = obj(
        "id" to num(b.id),
        "fqcn" to str(b.fqcn),
        "method" to str(b.method),
        "signature" to str(b.signature),
        "dexPc" to num(b.dexPc),
        "state" to str(b.state),
        "hitCount" to num(b.hitCount),
        "note" to str(b.note),
        "condition" to str(b.condition),
    )

    fun of(s: StepSnapshot): String = obj(
        "seq" to num(s.seq),
        "fqcn" to str(s.fqcn),
        "method" to str(s.method),
        "dexPc" to num(s.dexPc),
        "stackDepth" to num(s.stackDepth),
        "registers" to arr(s.registers.map { of(it) }),
    )

    fun of(n: ObjectNode): String = obj(
        "objectId" to num(n.objectId),
        "label" to str(n.label),
        "type" to str(n.type),
        "arrayLength" to num(n.arrayLength),
        "truncated" to bool(n.truncated),
        "fields" to arr(n.fields.map {
            obj(
                "name" to str(it.name),
                "type" to str(it.type),
                "value" to str(it.value),
                "objectId" to num(it.objectId),
                "expandable" to bool(it.expandable),
            )
        }),
    )

    fun of(s: DebugState): String = obj(
        "status" to str(s.status),
        "message" to str(s.message),
        "threadId" to num(s.threadId),
        "threadName" to str(s.threadName),
        "currentFrame" to num(s.currentFrame),
        "reason" to str(s.reason),
        "deoptWarning" to bool(s.deoptWarning),
        "frames" to arr(s.frames.map { of(it) }),
    )
}
