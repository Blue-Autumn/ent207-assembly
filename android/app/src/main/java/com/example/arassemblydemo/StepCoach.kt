package com.example.arassemblydemo

/** Non-secret facts owned by the app, never inferred from a conversation. */
data class CoachContext(
    val accountId: String,
    val runId: String,
    val contentVersion: String,
    val layer: Int,
    val stepId: String,
    val screenOnly: Boolean,
) {
    init {
        require(accountId.isNotBlank() && runId.isNotBlank())
        require(contentVersion.isNotBlank() && stepId.isNotBlank())
        require(layer in 1..5)
    }
}

enum class CoachSource { OFFLINE_REVIEWED }

data class CoachAnswer(val text: String, val recognized: Boolean, val source: CoachSource = CoachSource.OFFLINE_REVIEWED)

/** A future remote implementation must retain context and pass the same response gate. */
fun interface StepCoachProvider {
    fun answer(context: CoachContext, question: String): CoachAnswer
}

/** Fixed, reviewed local help. This is not a language model or online inference. */
class OfflineStepCoachProvider : StepCoachProvider {
    companion object {
        const val STEP_ID = "shelf-pair-preassembly"
        const val WHY = "为什么不能混层"
        const val SIMPLE = "再简单说"
        const val STUCK = "卡住怎么办"
    }

    override fun answer(context: CoachContext, question: String): CoachAnswer {
        if (context.contentVersion != ShelfRules.CONTENT_VERSION || context.stepId != STEP_ID) {
            return CoachAnswer("当前内容版本或步骤不在这份离线说明范围内。请返回项目检查；不能据此判断装配。", false)
        }
        val color = ShelfRules.colorName(context.layer)
        val prefix = if (context.screenOnly) "当前是屏幕练习，不代表已经找到或装好实物。\n\n" else ""
        val normalized = question.trim().trimEnd('？', '?', '。', '！', '!')
        val text = when (normalized) {
            WHY, "为什么不能混层装", "为什么不能混用不同层" ->
                "本步指定${color}层的两半，半片1和半片2一对一配对。即使另一层的形状相同，也不能替代本层指定实例。颜色来自标签登记，不是相机自动识别颜色。彩色层板允许A/B面混搭；有缺口的一边仍须按模型方向摆放，总装时也不能把缺口倒转。"
            SIMPLE, "再简单说一遍", "简单说一下" ->
                "找齐${color}层的两半；先核对对应榫和槽并摆正，对齐后沿实际接头方向向下直插。这里的向下按实物摆放理解，不是手机屏幕向下。装好后由你在装配页确认，说明本身不会推进进度。"
            STUCK, "卡住了怎么办", "插不进去怎么办" ->
                "先停止施力，不要强压。退回检查是否拿齐${color}层指定两半、榫和槽是否对应并对齐。已确认的方法是直插，不需要滑入、倾斜或在插入时旋转；彩色层板可以混用A/B面，重点检查缺口方向与接口是否对应。仍不顺畅就暂停并核对实物接头。这份说明看不到接触情况，不能判断具体卡点或给出新的插装路径。"
            else -> return CoachAnswer(prefix +
                "这份离线帮助只覆盖下方三类本步问题，无法理解或核验这条新问题。请使用对应问题按钮，或返回查看指导并核对实物；不能据此判断姿态、接合是否到位或装配是否完成。", false)
        }
        return CoachAnswer(prefix + text, true)
    }
}

data class CoachRequestToken internal constructor(val generation: Long, val context: CoachContext)

/** Last request wins; stopping, account/run/step changes and invalidation reject old replies. */
class CoachRequestGate {
    private var generation = 0L
    private var pending: CoachRequestToken? = null

    @Synchronized fun begin(context: CoachContext): CoachRequestToken {
        generation++
        return CoachRequestToken(generation, context).also { pending = it }
    }

    @Synchronized fun invalidate() { generation++; pending = null }

    /** Consume once so a provider cannot deliver duplicate answers for the same request. */
    @Synchronized fun accept(token: CoachRequestToken, currentContext: CoachContext): Boolean {
        if (pending != token || token.generation != generation) return false
        if (token.context != currentContext) { generation++; pending = null; return false }
        pending = null
        return true
    }
}
