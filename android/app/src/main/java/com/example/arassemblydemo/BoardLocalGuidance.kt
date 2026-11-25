package com.example.arassemblydemo

import kotlin.math.*

/** On-device coarse direction hints from declared ID positions. Never a seating or 6DoF verdict. */
internal object BoardLocalGuidance {
    data class Task(val id: String, val kind: String, val group: String, val parts: List<String>,
                    val stationary: List<String> = emptyList(), val members: List<String> = emptyList())
    data class Result(val status: String, val title: String, val message: String,
                      val part: String? = null, val angleDegrees: Double? = null,
                      val anchor: BoardFaceRules.Point? = null, val markerIds: List<Int> = emptyList(),
                      val axis: Int? = null, val targetLabel: String? = null,
                      val cueGeometry: BoardFaceRules.Projection? = null, val flipAxis: Int? = null,
                      val placement: BoardPlacementCue.Placement? = null, val slotTargets: List<BoardFaceRules.Point> = emptyList(), val installMotion:String? = null,
                      val inferredSlotTargets:List<BoardFaceRules.Point> = emptyList(),val notchCue:List<BoardFaceRules.Point> = emptyList(), val notchRelation:String? = null,
                      val backCue:List<BoardFaceRules.Point> = emptyList(),
                      val observedRow:Int? = null, val targetRow:Int? = null,
                      val alignedPlanes:Map<String, BoardFaceRules.Projection> = emptyMap())

    private val layerGroups = (1..5).map { "层${it}板" }.toSet()
    private const val NOTCH_AXIS = 0 // Actual shelf CAD: the notched outside edge is at +X, seam runs along Z.

    fun evaluate(sites: List<BoardFaceRules.Site>, detections: List<BoardFaceRules.Detection>, task: Task,
                 measured: MeasuredBoardProjection.Catalog? = null, width:Int=0, height:Int=0, history:FinalAssemblyGuidance.History?=null, atMs:Long=0, slotQuads:List<List<BoardFaceRules.Point>> = emptyList()): Result {
        if(FinalAssemblyGuidance.supports(task))return FinalAssemblyGuidance.evaluate(task,sites,detections,measured,width,height,history,atMs,slotQuads)
        val expected = task.parts.distinct()
        val own = sites.filter { it.part in expected }
        val known = own.associateBy { it.id }
        val seen = detections.filter { it.id in known }
        val ids = seen.map { it.id }.distinct().sorted()
        var cuePlanes = emptyMap<String, BoardFaceRules.Projection>()
        fun partCenter(part: String) = cuePlanes[part]?.center ?: seen.filter { known[it.id]?.part == part }.takeIf { it.isNotEmpty() }
            ?.map { center(it) }?.let { p -> BoardFaceRules.Point(p.map { it.x }.average(), p.map { it.y }.average()) }
        fun targetLabel(part: String?): String? {
            if (part == null) return null
            val p = partCenter(part) ?: return "箭头指的这块"
            val other = expected.firstOrNull { it != part }?.let { partCenter(it) } ?: return "箭头指的这块"
            val dx = p.x-other.x; val dy = p.y-other.y
            val span = cuePlanes.values.flatMap { it.axes }.maxOfOrNull { hypot(it.end.x-it.start.x,it.end.y-it.start.y) } ?: 0.0
            val clearSeparation = max(30.0,span*.18)
            return when {
                abs(dx) >= abs(dy)*1.25 && abs(dx) > clearSeparation -> if (dx < 0) "左手边这块" else "右手边这块"
                abs(dy) >= abs(dx)*1.25 && abs(dy) > clearSeparation -> if (dy < 0) "上面这块" else "下面这块"
                else -> "箭头指的这块"
            }
        }
        fun result(status: String, title: String, message: String, part: String? = null,
                   angle: Double? = null, axis: Int? = null, flipAxis: Int? = null): Result {
            val plane = part?.let { cuePlanes[it] }
            val anchor = plane?.center ?: seen.filter { part == null || known[it.id]?.part == part }.takeIf { it.isNotEmpty() }
                ?.map { center(it) }?.let { p -> BoardFaceRules.Point(p.map { it.x }.average(), p.map { it.y }.average()) }
            return Result(status, title, message, part, angle, anchor, ids, axis, targetLabel(part), plane, flipAxis,
                alignedPlanes = if (task.kind == "prepare" && status == "direction_ok") cuePlanes else emptyMap())
        }

        if (own.isEmpty()) return Result("need_view", "先登记这些标签", "当前没有这一步的面与角位登记；仍可看模型完成装配。")
        if (seen.isEmpty()) {
            val allKnown = sites.associateBy { it.id }
            val other = detections.filter { it.id in allKnown && validQuad(it) }
            val groups = other.map { allKnown.getValue(it.id).part.substringBeforeLast('-') }.distinct()
            if (other.size >= 2 && other.map { it.id }.distinct().size == other.size && groups.size == 1) {
                val points = other.map { center(it) }
                return Result("wrong_part", "换成这一步的零件",
                    "拍到的是${groupLabel(groups.single())}，这一步要用${groupLabel(task.group)}。",
                    anchor = BoardFaceRules.Point(points.map { it.x }.average(), points.map { it.y }.average()),
                    markerIds = other.map { it.id }.sorted())
            }
            return Result("searching", "对准这一步的零件", "把当前零件的标签放进画面，方向提示会自动出现。")
        }
        if (seen.size != ids.size || seen.any { !validQuad(it) })
            return Result("need_view", "把手机稍微拿稳", "标签暂时不够清楚，请拍全当前零件及其分散标签。", markerIds = ids)
        val observations = expected.associateWith { measured?.observe(it, own, seen) ?: BoardFaceRules.observe(it, own, seen) }
        cuePlanes = observations.values.mapNotNull { o -> o.projection?.let { o.part to it } }.toMap()
        val visible = observations.values.filter { it.ids.isNotEmpty() }
        if (task.kind != "prepare" || expected.size != 2) {
            // Projected axes from different planes cannot distinguish a correct installation from perspective.
            val missingMember = task.members.firstOrNull { observations[it]?.ids.isNullOrEmpty() }
            if (missingMember != null) return result("need_view", "把当前要装的零件拍进来",
                "先对准${label(missingMember)}；旁边已装好的零件不会覆盖这一步的提示。", missingMember)
            return result("identity_only", "已识别当前零件", "按模型把缺口和接口朝向摆好；这里还不能仅凭标签确认总装方向或是否插到底。",
                task.members.firstOrNull { observations[it]?.ids?.isNotEmpty() == true } ?: visible.first().part)
        }
        val fixed = task.stationary.firstOrNull { it in expected } ?: expected.first()
        val moving = task.members.firstOrNull { it in expected && it != fixed } ?: expected.first { it != fixed }
        val absent = listOf(fixed, moving).firstOrNull { observations.getValue(it).ids.isEmpty() }
        if (absent != null) return result("need_view", "把另一半也拍进来", "两块先平放在桌面，让它们一起进入画面。", absent)

        val isLayer = task.group in layerGroups && own.all { it.axis == 1 }
        val a = if (isLayer) withTwoTagAxis(observations.getValue(fixed), own, seen) else observations.getValue(fixed)
        val b = if (isLayer) withTwoTagAxis(observations.getValue(moving), own, seen) else observations.getValue(moving)
        fun clearerView(o: BoardFaceRules.Observation, status: String = "need_view"): Result {
            val advice = when (o.ids.size) {
                0, 1 -> "再拍进这块上另一枚离得远的标签。"
                2 -> "再露出这块的另一个角，或稍微靠近拍清标签。"
                else -> "标签已经看到。把手机稍微拿稳，靠近一点拍清。"
            }
            return result(status, "拍清${targetLabel(o.part)}", advice, o.part)
        }
        if (a.face == null || b.face == null) return result("need_view", "拍清两半的这一面", "每半只露出同一面的标签；调整角度后再看方向。")
        if (isLayer) {
            val ax = a.heading(NOTCH_AXIS); val bx = b.heading(NOTCH_AXIS)
            if (ax == null || bx == null) return result("need_view", "再露出两边的标签",
                "每半拍清左右分开的两枚标签，或三枚分散标签；A、B 面可以混搭。")
            val angle = BoardFaceRules.angle(ax - bx)
            if (abs(angle) <= 22.0) return result("direction_ok", "缺口方向一致", "保持这个方向，对准接头再直插；是否接好由你确认。", moving, axis = NOTCH_AXIS)
            if (abs(angle) < 42.0) return result("need_view", "先把两半平放", "把两半和接缝一起拍清，避免斜着拿造成方向误差。", moving)
            return result("rotate", "转动${targetLabel(moving)}", "另一块先不动。平放着转，让两块的缺口朝同一边。", moving, angle, NOTCH_AXIS)
        }
        val check = BoardFaceRules.compareFlatPair(a, b)
        return when (check.status) {
            "exposed_faces_differ" -> {
                // Face labels alone identify which surface is visible, but do not establish the flat-view
                // precondition. Require usable axes on both pieces before showing a physical flip action.
                if (a.axes.size < 2 || b.axes.size < 2) clearerView(if (a.axes.size < 2) a else b, "face_check")
                else result("flip", "把${targetLabel(moving)}翻一下面",
                    "另一块不动，沿板上的提示翻一下面。放平后我再看。", moving,
                    flipAxis = BoardFaceRules.preferredFlipAxis(a, b))
            }
            "same_face_coarse_direction_ok" -> {
                val placement=BoardPlacementCue.suggest(a,b,measured)
                if(placement!=null)result("direction_ok", "移开${targetLabel(moving)}再对齐",
                    "方向对了。拿起这块，移到虚影处，再对准接头。", moving).copy(placement=placement)
                else result("direction_ok", "两半方向一致", "保持这个方向，让接头相对，再从上方接合。", moving)
            }
            "rotate_in_plane" -> result("rotate", "转动${targetLabel(moving)}", "另一块先不动。让这块贴着桌面，按箭头转。", moving, check.angleDegrees)
            else -> {
                val incomplete = listOf(a,b).firstOrNull { it.axes.size < 2 }
                if(incomplete != null) clearerView(incomplete)
                else result("need_view", "移到两块中间看看", "暂时看不准转向，稍微换个拍摄角度。")
            }
        }
    }

    /** Two near-horizontal author corner positions recover only +X, never a full plane or bitmap angle. */
    private fun withTwoTagAxis(observation: BoardFaceRules.Observation, sites: List<BoardFaceRules.Site>,
                               detections: List<BoardFaceRules.Detection>): BoardFaceRules.Observation {
        if (observation.face == null || observation.ids.size != 2 || observation.axes.isNotEmpty()) return observation
        val own = sites.filter { it.part == observation.part && it.face == observation.face }.associateBy { it.id }
        if (own.size < 3 || own.values.any { it.axis != 1 }) return observation
        val a = own[observation.ids[0]] ?: return observation
        val b = own[observation.ids[1]] ?: return observation
        val dx = b.center[0] - a.center[0]; val dz = b.center[2] - a.center[2]
        val width = own.values.maxOf { it.center[0] } - own.values.minOf { it.center[0] }
        if (!dx.isFinite() || !dz.isFinite() || width < 1.0 || abs(dx) < width * .55 || abs(dz) > abs(dx) * .12) return observation
        val detected = detections.associateBy { it.id }
        val p = center(detected[a.id] ?: return observation); val q = center(detected[b.id] ?: return observation)
        val separation = hypot(q.x - p.x, q.y - p.y)
        if (separation < 55.0) return observation
        return observation.copy(reason = "coarse_single_axis_from_two_tags", axes = mapOf(0 to BoardFaceRules.Point((q.x-p.x)/dx, (q.y-p.y)/dx)), extentPx = separation)
    }

    private fun validQuad(d: BoardFaceRules.Detection): Boolean {
        val p = d.corners
        if (p.size != 4 || p.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val area = abs(p.indices.sumOf { i -> p[i].x*p[(i+1)%4].y - p[(i+1)%4].x*p[i].y }) / 2
        return area >= 16.0
    }
    private fun center(d: BoardFaceRules.Detection) = BoardFaceRules.Point(d.corners.map { it.x }.average(), d.corners.map { it.y }.average())
    private fun groupLabel(group: String): String = mapOf("层1板" to "紫色层板", "层2板" to "蓝色层板",
        "层3板" to "肉色层板", "层4板" to "黄色层板", "层5板" to "绿色层板",
        "左侧板" to "左侧框", "右侧板" to "右侧框")[group] ?: group
    private fun label(part: String): String {
        val name = mapOf("层1板" to "紫色层板", "层2板" to "蓝色层板", "层3板" to "肉色层板", "层4板" to "黄色层板", "层5板" to "绿色层板")
        val prefix = part.substringBeforeLast('-')
        return (name[prefix] ?: prefix) + "这一半"
    }

    /** Camera-time stability only; state is deliberately discarded whenever the task or camera changes. */
    class Stabilizer {
        data class Update(val result: Result, val notify: Boolean)
        private var candidate: Result? = null
        private var since = 0L
        private var lastAt = -1L
        private var count = 0
        private var notified: Result? = null
        private var confirmed = false
        private var lastActionAt = -1L
        private val actionable = setOf("direction_ok", "rotate", "flip", "wrong_part",
            "install_base_heading","install_notch_hint","install_base_flip","install_base_ready","install_turn","install_stand","install_row_ok","install_wrong_row","install_wrong_part","install_oriented")
        fun reset() { candidate = null; notified = null; since = 0L; lastAt = -1L; count = 0; confirmed = false; lastActionAt = -1L }
        fun update(raw: Result, nowMs: Long): Update {
            if (lastAt >= 0 && (nowMs < lastAt || nowMs-lastAt > 900)) reset()
            if (raw.status !in actionable) {
                // Keep only the already-confirmed semantic identity over a short detector dropout.
                // Return the CURRENT neutral observation: never replay old geometry during loss.
                if (!confirmed || nowMs-lastActionAt !in 0..600) { candidate = null; count = 0; confirmed = false }
                lastAt = nowMs
                return Update(raw, false)
            }
            if (candidate == null || !same(candidate!!, raw) || nowMs-lastActionAt > 600) { candidate = raw; since = nowMs; count = 1; confirmed = false }
            else if (nowMs > lastAt) count++
            lastActionAt = nowMs
            lastAt = nowMs
            if (count < 3 || nowMs-since < 500) return Update(raw.copy(status="need_view", title="正在看方向", message=if(raw.status.startsWith("install_"))"扶稳零件，稍等一下。" else "保持两半平放，稍等一下。", angleDegrees=null, placement=null, backCue=emptyList(), observedRow=null, targetRow=null, alignedPlanes=emptyMap()), false)
            confirmed = true
            val shouldNotify = notified == null || !same(notified!!, raw)
            if (shouldNotify) notified = raw
            return Update(raw, shouldNotify)
        }
        private fun same(a: Result, b: Result): Boolean = a.status == b.status && a.part == b.part && a.flipAxis == b.flipAxis &&
            (a.placement==null)==(b.placement==null) && a.installMotion==b.installMotion &&
            a.observedRow==b.observedRow && a.targetRow==b.targetRow &&
            (a.status != "wrong_part" || a.message == b.message) &&
            (if (a.angleDegrees == null || b.angleDegrees == null) a.angleDegrees == b.angleDegrees
             else abs(BoardFaceRules.angle(a.angleDegrees-b.angleDegrees)) <= 18.0)
    }
}
