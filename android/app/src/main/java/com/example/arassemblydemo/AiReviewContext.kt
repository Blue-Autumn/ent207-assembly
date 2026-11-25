package com.example.arassemblydemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Uses the installed tutorial and current ID registration; author placeholder coordinates are not poses. */
internal class AiReviewContext private constructor(
    private val owner:String?, private val revision:String, private val bindingSnapshot:String,
    private val assembly:String, private val actions:String, val task:JSONObject,
    val bindings:Map<String,Set<Int>>,val faces:BoardFaceRegistry,private val faceSnapshot:String?,
    private val measured:MeasuredBoardProjection.Catalog, private val uiLanguage:String
) {
    val key:String=MessageDigest.getInstance("SHA-256").digest(
        JSONArray(listOf(owner,revision,bindingSnapshot,faces.fingerprint,assembly,actions,task.getString("id"))).toString().toByteArray()
    ).joinToString(""){"%02x".format(it)}
    fun isCurrent(context:Context):Boolean {
        val prefs=context.getSharedPreferences("assembly-bindings-v1",0)
        return LocalAccounts(context).current()?.id==owner && prefs.getString("revision","unregistered")==revision &&
            prefs.getString("bindings","{}")==bindingSnapshot && prefs.getString(BoardFaceRegistry.PREF_SITES,null)==faceSnapshot
    }
    fun partFor(id:Int):String?=bindings.entries.firstOrNull { id in it.value }?.key
    /** Photo-only teaching geometry, never exported as a live-camera observation. */
    fun photoCue(frame:JointPhotoFrame,assessment:String,localCheck:JSONObject?):AiPhotoCue.Lesson? {
        if(assessment!="needs_adjustment")return null
        val current=BoardLocalGuidance.Task(task.getString("id"),task.getString("kind"),
            task.getString("group"),strings(task.getJSONArray("context")),strings(task.getJSONArray("stationary")),
            strings(task.getJSONArray("members")))
        if(current.kind=="install"){
            if(!LayerPhotoReview.applies(task)||localCheck==null)return null
            return AiPhotoCue.rowPlan(assessment,current,frame.token,localCheck)
        }
        val detections=frame.markers.filter { marker->marker.points.size==8&&marker.points.all { it.isFinite() }&&
            (0..3).all { k->marker.points[2*k]>=0&&marker.points[2*k]<frame.bitmap.width&&
                marker.points[2*k+1]>=0&&marker.points[2*k+1]<frame.bitmap.height } }
            .map { marker->BoardFaceRules.Detection(marker.id,(0..3).map { k->
                BoardFaceRules.Point(marker.points[2*k].toDouble(),marker.points[2*k+1].toDouble()) }) }
        return AiPhotoCue.plan(assessment,current,faces.sites,detections,frame.token,measured)
    }
    fun build(frame:JointPhotoFrame,question:String):JSONObject {
        val expected=strings(task.getJSONArray("context"))
        val focus=if(task.optString("kind")=="prepare")expected else strings(task.getJSONArray("members"))
        val references=strings(task.getJSONArray("stationary"))
        val usableMarkers=frame.markers.filter { marker->marker.points.size==8&&marker.points.all { it.isFinite() }&&
            (0..3).all { k->marker.points[2*k]>=0&&marker.points[2*k]<frame.bitmap.width&&
                marker.points[2*k+1]>=0&&marker.points[2*k+1]<frame.bitmap.height } }
        val visibleByPart=usableMarkers
            .mapNotNull { marker->partFor(marker.id)?.let { it to marker.id } }
            .groupBy({it.first},{it.second}).mapValues { it.value.distinct().sorted() }
        val seen=visibleByPart.keys
        val accept=task.optString("authoredAccept")
        val checks=if(accept.isNotBlank())JSONArray(listOf(accept)) else task.getJSONArray("suggestedChecks")
        return JSONObject().put("schema","assembly-step-context-1").put("uiLanguage",uiLanguage).put("assembly",assembly)
            .put("actionsSource",actions).put("bindingRevision",revision)
            .put("task",JSONObject().put("id",task.getString("id")).put("title",task.getString("title"))
                .put("kind",task.getString("kind")).put("group",task.getString("group")).put("instruction",instruction())
                .put("userNote",task.optString("instruction")).put("checks",checks)
                .put("checksSource",if(accept.isNotBlank())"user-authored" else "suggested")
                .put("members",task.getJSONArray("members")).put("stationary",task.getJSONArray("stationary"))
                .put("visualFocus",visualFocus()))
            .put("parts",JSONArray(expected.map { id -> JSONObject().put("id",id).put("name",partName(id))
                .put("visibleMarkers",JSONArray(visibleByPart[id].orEmpty()))
                .put("userReference",AiReplyText.userReference(id,visibleByPart[id].orEmpty(),uiLanguage=="en"))
                .put("role",if(id in focus)"current_target" else "reference")
                .put("facePolicy",if(id.startsWith("层"))"unrestricted" else "cad_relative_orientation")
                .put("markers",JSONArray(bindings[id].orEmpty().sorted())) }))
            .put("observedParts",JSONArray(visibleByPart.keys.sorted().map { id -> JSONObject().put("id",id)
                .put("name",partName(id)).put("visibleMarkers",JSONArray(visibleByPart.getValue(id)))
                .put("userReference",AiReplyText.userReference(id,visibleByPart.getValue(id)))
                .put("role",when(id){in focus->"current_target";in references->"reference";else->"outside_task"}) }))
            .put("reviewScope",JSONObject().put("version",2).put("focusParts",JSONArray(focus))
                .put("referenceParts",JSONArray(references)).put("reviewEarlierAssembly",false)
                .put("layerFacesUnrestricted",true).put("layerPreservedCadAxis","X")
                .put("localInterfaceCheckSufficient",true).put("wholeBoardPhotoRequired",false)
                .put("frameDirectionEvidence",JSONArray(listOf("joint_match","aligned")))
                .put("instruction",if(task.optString("kind")=="prepare")"本步两半均为当前目标；固定的一半也需要核对，stationary仅说明动作基准。只检查这两件的缺口和当前接口。"
                    else "只判断本步目标零件及它当前接入的接口。前序零件是参照，不对已完成的整架重新验收；旧问题不能覆盖当前步骤。"))
            .put("rules",JSONArray(listOf(
                "总装各张照片的framePairCheck包含本机核对的当前方向与孔位。install_turn、install_base_flip、install_wrong_row要先纠正；install_row_ok只说明对着目标孔，仍从照片看接缝。",
                "这是五层隔板架。层1到层5从底部依次是紫色、蓝色、肉色、黄色、绿色。每层只使用指定的两半。",
                "彩色层板两半允许A+A、B+B、A+B任意组合，预装和总装都不因为正反混面报错，也不要求为统一面向拆开。带缺口的边仍须朝CAD规定的一端，不能反转缺口方向。先将两件平放再比较粗轴向。",
                "侧框和背板仍按CAD终态相对面向和方向安装。必须有接口或参照板关系才能确定朝里朝外；照片只看见A或B不说明装错。彩色层板的正反豁免不豁免缺口方向。",
                "四个侧框半组件不能互换，内部已经胶粘的直线接缝保持整体，不要求拆开或消除胶痕。",
                "先将一侧框平放、插上层板，再盖上另一侧框；已确认的接头动作是对准后直插，不发明拧转或硬压操作。",
                "辅助图细框只标出摄像头识别的标签及其零件归属，颜色区分零件而不表示正确或错误；它不是板件轮廓。",
                "标签ID已恢复作者录入的A/B面及角位。灰框framePairCheck的joint_match说明当前接头两侧标签同面且连接端对应，配合照片中接缝齐平即可作局部肯定；aligned是多码粗轴向依据，也仍需看接缝。face_mismatch、rotate、joint_mismatch则须纠正。localOrientation粗轴向不足不否定有效joint_match。单枚标签能辨面，不能单独证明已接好；不以码图案顶边猜板件方向，也不把这些结果当完整总装位姿。",
                "照片可能有桌面备用件；不在当前步骤的标签出现不直接说明装错。接口不在画面中时请指定补拍位置。",
                "第一张就可以拍一处接头近照，带上接缝两侧相邻标签即可核对配对与局部接合，不要求先有全景、拍到另一端或每半三四枚码。多图可互补，最新照片已明确修正的就按当前状态回答，旧错误不覆盖它；当前关键关系仍模糊才补拍。",
                "对用户使用照片中看得见的特征或标签号，例如贴076标签的这一半；实体ID仅用于匹配，不能把左侧板-1或层2板-2直接念给用户。模型里的左右不等于照片里的左右。",
                "细榫缝、打印纹理或胶粘痕迹本身不等于未接合。接缝连续、两侧齐平且没有可见开口时，不要求再平移、拆开重拼或消除细线。看不清深度只补拍，不报确定错装。",
                "只给本步目标最相关的一处检查或下一操作，不让先前组件的可疑状态覆盖当前接头；装配完成由用户确认。")))
            .put("question",question)
            .put("localOrientation",faces.analyze(frame.markers,task))
            .put("framePairCheck",AiFrameOrientation.analyze(BoardLocalGuidance.Task(task.getString("id"),task.getString("kind"),
                task.getString("group"),expected,references,strings(task.getJSONArray("members"))),faces.sites,
                usableMarkers.map { marker->BoardFaceRules.Detection(marker.id,(0..3).map { k->
                    BoardFaceRules.Point(marker.points[2*k].toDouble(),marker.points[2*k+1].toDouble()) }) },frame.token,measured,frame.bitmap.width,frame.bitmap.height))
            .put("modelReference",JSONObject().put("available",false))
            .put("quality",JSONObject().put("sameFramePhotoAndMarkers",true)
                .put("seenExpectedParts",JSONArray(expected.filter { it in seen }))
                .put("notSeenCurrentParts",JSONArray(focus.filterNot { it in seen }))
                .put("otherVisibleParts",JSONArray(seen.filterNot { it in expected }))
                .put("unknownMarkerIds",JSONArray(frame.markers.filter { partFor(it.id)==null }.map { it.id }.distinct()))
                .put("duplicateMarkerIds",JSONArray(frame.markers.groupingBy { it.id }.eachCount().filterValues { it>1 }.keys))
                .put("notSeenDoesNotMeanMissing",true))
            .put("markerPoseCalibrated",false).put("automaticAssemblyVerification",false)
    }
    private fun visualFocus():String = when {
        LayerPhotoReview.applies(task) -> "先看正在装入的这层板的缺口是否朝向底框规定的一端。目标接口是这一层的下沿凸榫插入灰框槽的位置。板中央竖缝已在预装完成。方向能回答就直接说明；照片可见的明显翘起或离缝另提一句。"
        task.optString("kind")!="prepare" -> "本步新装入零件与相邻板的接口及对应槽位；已经装好的旧部件只作参照。"
        task.optString("group").startsWith("层") -> "两半交界的榫接是否对准、是否齐平，以及带缺口的一边是否同向。"
        task.optString("group").contains("侧板") -> "检查正在拍的这一处榫接。接头两侧登记标签同面且连接端对应、此处接缝齐平就足够作局部判断，不必拍全板或另一端。半件中间的胶粘直线不用处理。"
        else -> "两半交界处的榫接和轮廓是否连续、板面是否齐平。"
    }
    fun observation(frame:JointPhotoFrame,width:Int=frame.bitmap.width,height:Int=frame.bitmap.height):JSONObject {
        require(width>0&&height>0)
        val sx=width.toDouble()/frame.bitmap.width;val sy=height.toDouble()/frame.bitmap.height
        val markers=frame.markers.filter { it.points.size==8 && it.points.all { v->v.isFinite() } &&
            (0..3).all { k -> it.points[2*k]>=0 && it.points[2*k]<frame.bitmap.width && it.points[2*k+1]>=0 && it.points[2*k+1]<frame.bitmap.height }
        }.map { marker ->
            JSONObject().put("id",marker.id).apply { partFor(marker.id)?.let { put("part",it) } }
                .put("corners",JSONArray(marker.points.mapIndexed { index,v -> v*(if(index%2==0)sx else sy) }))
        }
        return JSONObject().put("captureId",frame.token).put("width",width).put("height",height)
            .put("markers",JSONArray(markers))
    }
    private fun instruction():String {
        val id=task.getString("id");val layer=task.getString("group").startsWith("层")
        return when {
            task.getString("kind")=="prepare" && layer -> "两半大面平放，正反面可以混用。核对缺口方向后接头相对，固定一半，另一半从上方垂直接合；接缝已齐平就不需再移动。"
            task.getString("kind")=="prepare" -> "把这两件大面平放，先核对两半正反和转向一致，再从上方接合。内部已经粘死的小板保持整体。"
            id=="install:左侧板" -> "把拼好的模型左侧框平放在桌面上，槽口朝上，作为后面安装的基准。"
            layer -> "拿起刚拼好的层板并立起，正反面可以混用，但缺口必须朝CAD示范的同一端；对准平放侧框上的对应槽位直插，只检查本层当前接口。"
            id=="install:右侧板" -> "拿起拼好的另一侧框，大面保持与桌面平行，对准五层板的自由端，再直插盖合。逐个核对槽位。"
            else -> "把拼好的背板摆到图示位置，对准接头，沿本步箭头方向直插。先核对两端接口，卡住时先退回检查。"
        }
    }
    companion object {
        private fun strings(array:JSONArray)=(0 until array.length()).map { array.getString(it) }
        fun partName(id:String):String {
            return AiReplyText.partDescription(id)
        }
        fun load(context:Context,taskId:String):AiReviewContext {
            val flow=JSONObject(context.assets.open("shelf-phone/workflow.json").bufferedReader().use { it.readText() })
            val tasks=flow.getJSONArray("tasks").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val task=tasks.single { it.getString("id")==taskId }
            val known=tasks.flatMap { strings(it.getJSONArray("context")) }.toSet()
            val prefs=context.getSharedPreferences("assembly-bindings-v1",0)
            val raw=prefs.getString("bindings","{}")!!;val json=JSONObject(raw)
            val entries=json.keys().asSequence().toList().flatMap { part -> val values=json.getJSONArray(part)
                (0 until values.length()).map { AssemblyBinding(part,it,values.getInt(it)) } }
            val bindings=AssemblyMarkerBindings.validate(entries,known).mapValues { it.value.toSet() }
            val faces=BoardFaceRegistry.load(context,bindings)
            val measured=runCatching { MeasuredBoardProjection.parse(context.assets.open("shelf-phone/measured-board-planes.json")
                .bufferedReader().use { it.readText() },flow.getString("assembly"),faces.sites) }
                .getOrElse { MeasuredBoardProjection.unavailable("AI photo measured geometry unavailable") }
            return AiReviewContext(LocalAccounts(context).current()?.id,prefs.getString("revision","unregistered")!!,
                raw,flow.getString("assembly"),flow.getString("sourceSha256"),task,bindings,
                faces,prefs.getString(BoardFaceRegistry.PREF_SITES,null),measured,if(AssemblyLanguage.english(context)) "en" else "zh-CN")
        }
    }
}
