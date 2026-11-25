package com.example.arassemblydemo

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID

/** One-pair pilot. Stored confirmation is a user's declaration, never a vision verdict. */
class ShelfDemoActivity : ComponentActivity() {
    private val screenOnly by lazy { intent.getBooleanExtra("screen_validation",false) }
    private val accounts by lazy { LocalAccounts(this) }
    private var openedAccountId: String? = null
    private val prefs by lazy {
        val name = openedAccountId?.let { accounts.progressPrefsName(it,screenOnly) }
            ?: if (screenOnly) "shelf_screen_validation" else "shelf_guest_physical"
        getSharedPreferences(name, MODE_PRIVATE)
    }
    private lateinit var session: ShelfSession
    private lateinit var root: LinearLayout
    private var page = "home"
    private var scanning = false
    private var notice = ""
    private var recoveryBlocked = false
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scanning = false
        if (!::session.isInitialized || !sameAccount()) return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            if (result.data?.getBooleanExtra("observation_invalidated",false) == true) session = session.invalidate()
            session = session.scan(result.data?.getIntArrayExtra("marker_ids")?.toList() ?: emptyList())
        } else session = session.invalidate()
        android.util.Log.i("ShelfValidation", "mode=${if(screenOnly) "SCREEN" else "PHYSICAL"} ids=${result.data?.getIntArrayExtra("marker_ids")?.joinToString()} roles=${session.seenRoles} message=${session.message}")
        page = "find"; render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAccountId = accounts.current()?.id
        val stored = prefs.getString("content_version",null)
        session = if (stored == null) ShelfSession.start(1) else {
            ShelfSession.restore(stored,prefs.getString("run_id","") ?: "",prefs.getInt("layer",1),prefs.getBoolean("confirmed",false))
                ?: ShelfSession.start(1).also { recoveryBlocked = true; notice = "旧记录与当前内容不一致，已停止继续。请开始新一轮。" }
        }
        if (!screenOnly && stored != null && prefs.getString("record_mode",null) != "PHYSICAL") {
            recoveryBlocked = true
            notice = "旧记录缺少测试/实物来源标记，已保留但不作为实物完成证明。请明确开始新一轮并重新核对贴标。"
        }
        if (stored == null) save(session)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,20,28,20); setBackgroundColor(Color.rgb(247,249,252)) }
        root.setOnApplyWindowInsetsListener { v,i ->
            @Suppress("DEPRECATION")
            v.setPadding(28,20+i.systemWindowInsetTop,28,20+i.systemWindowInsetBottom); i
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        render()
    }

    override fun onStop() {
        if (::session.isInitialized && !scanning) { session = session.invalidate(); if (page == "guide") page = "find" }
        super.onStop()
    }
    override fun onStart() { super.onStart(); if (::session.isInitialized && sameAccount()) render() }

    private fun sameAccount(): Boolean {
        if (accounts.current()?.id != openedAccountId) { finish(); return false }
        return true
    }

    private fun openCoach() {
        if (!sameAccount()) return
        val facts = CoachContext(openedAccountId ?: "guest",session.runId,session.contentVersion,
            session.layer,OfflineStepCoachProvider.STEP_ID,screenOnly)
        if (page == "guide") {
            val questions = arrayOf(OfflineStepCoachProvider.WHY,OfflineStepCoachProvider.SIMPLE,OfflineStepCoachProvider.STUCK)
            AlertDialog.Builder(this).setTitle("本步离线帮助 · 非在线AI").setItems(questions) { _, index ->
                if (sameAccount() && session.runId == facts.runId && page == "guide") {
                    val answer = OfflineStepCoachProvider().answer(facts,questions[index])
                    AlertDialog.Builder(this).setTitle(questions[index]).setMessage(answer.text)
                        .setPositiveButton("回到本步",null).show()
                }
            }.setNegativeButton("返回",null).show()
        } else startActivity(StepCoachActivity.createIntent(this,facts))
    }

    private fun guideImage(name: String, description: String) {
        root.addView(ImageView(this).apply {
            adjustViewBounds = true; contentDescription = description
            setImageBitmap(assets.open("shelf-guide/$name").use { android.graphics.BitmapFactory.decodeStream(it) })
        },LinearLayout.LayoutParams(-1,-2))
    }

    private fun save(candidate: ShelfSession): Boolean {
        if (!sameAccount()) return false
        val record = org.json.JSONObject().put("version",candidate.contentVersion).put("runId",candidate.runId)
            .put("accountId",openedAccountId ?: "guest").put("layer",candidate.layer).put("confirmed",candidate.confirmed).put("evidenceKind",if(screenOnly) "SCREEN_WORKFLOW_ONLY" else if(candidate.confirmed) "USER_PHYSICAL_CONFIRMATION" else "PHYSICAL_RUN_UNCONFIRMED").toString()
        val ok = prefs.edit().putString("record_mode",if(screenOnly) "SCREEN" else "PHYSICAL").putString("run_${candidate.runId}",record).putString("content_version",candidate.contentVersion).putString("run_id",candidate.runId)
            .putInt("layer",candidate.layer).putBoolean("confirmed",candidate.confirmed).commit()
        if (ok) session = candidate else notice = "保存失败，未更新完成状态。请重试。"
        return ok
    }

    private fun label(text: String, size: Float = 18f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(28,39,58)); setPadding(0,14,0,14)
        root.addView(this)
    }
    private fun action(text: String, enabled: Boolean = true, click: () -> Unit) = Button(this).apply {
        this.text = text; isEnabled = enabled; isAllCaps = false; setOnClickListener { if (sameAccount()) click() }; root.addView(this,LinearLayout.LayoutParams(-1,-2))
    }
    private fun sideFitPage() {
        val key = "side_fit_v1_${session.runId}"
        label("紫层与侧框 · 单侧试装",22f)
        action("扫码核对侧框的两个半件") {
            startActivity(Intent(this,MarkerLiveTestActivity::class.java).putExtra("frame_assembly",true))
        }
        action("看紫层对应的两个槽位") { page="sideGuide"; render() }
        label("拿已拼好的紫层和一侧预装框。先找到对应凸起和凹口，对齐后按实际配合方向直插。内部长销保持预装。遇阻就停，记下实际情况。")
        label("先沿用紫板已有的四枚码。观察拿取、对齐和插入前，手或侧框会不会挡住需要看的码。")
        val fit = listOf("选试装结果","已顺利到位","码片碰到侧框","接头未对准或原因未定")
        val view = listOf("选插入前的可见性","拿取对齐时四码可见","手或侧框挡住部分码","当时未观察")
        fun picker(items: List<String>, saved: String?): Spinner {
            return Spinner(this).also {
                it.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,items)
                it.setSelection(items.indexOf(saved).coerceAtLeast(0))
                root.addView(it)
            }
        }
        val fitChoice = picker(fit,prefs.getString("${key}_fit",null))
        val viewChoice = picker(view,prefs.getString("${key}_visibility",null))
        action("保存我的试装观察") {
            if (fitChoice.selectedItemPosition==0 || viewChoice.selectedItemPosition==0) {
                Toast.makeText(this,"请先选择两项实际观察",Toast.LENGTH_SHORT).show()
            } else if (prefs.edit().putString("${key}_fit",fitChoice.selectedItem.toString())
                    .putString("${key}_visibility",viewChoice.selectedItem.toString()).commit()) {
                notice="已保存这次侧框试装观察。"; page="progress"; render()
            } else Toast.makeText(this,"保存失败，请重试",Toast.LENGTH_SHORT).show()
        }
        action("稍后试装 / 返回") { page="progress"; render() }
    }

    private fun render() {
        root.removeAllViews()
        label(if(screenOnly) "屏幕联调 · 不计实物进度" else "层板装配助手",26f)
        label(accounts.current()?.let { "本机账号：${it.name}" } ?: if (screenOnly) "访客 · 仅屏幕测试" else "本机试用 · 进度保存在这台手机",15f)
        label("${ShelfRules.colorName(session.layer)}层 · 两半预装",21f)
        if (notice.isNotEmpty()) label(notice)
        when(page) {
            "home" -> {
                label(if(screenOnly) "用电脑屏幕显示测试码，验证扫码和流程。测试记录独立保存，不登记贴标或真实装配。" else "先找齐指定的两半，再看说明并动手装配。紫层拼好后，可从已保存进度进入侧框试装。")
                label(if(session.confirmed) { if(screenOnly) "已保存：屏幕流程测试确认。" else "已保存：你确认本层两半已装好。" } else "本轮尚未确认完成。")
                action(if(session.confirmed) "查看已保存进度" else "开始 / 继续找件",!recoveryBlocked) { page = if(session.confirmed) "progress" else "find"; render() }
                action("选择目标层 / 开始新一轮") {
                    AlertDialog.Builder(this).setTitle("选择本轮目标层（旧确认将被替换）")
                        .setItems((1..5).map { "第${it}层 · ${ShelfRules.colorName(it)}" }.toTypedArray()) { _, which ->
                            if(save(ShelfSession.start(which+1,UUID.randomUUID().toString()))) { recoveryBlocked = false; notice = "已开始新一轮。请核对本层试点标签。"; page = "find" }; render()
                        }.setNegativeButton("取消",null).show()
                }
                action("试点标签说明",!recoveryBlocked && !screenOnly) { page = "labels"; render() }
                action("紫色试点图解 · 待实物核准",session.layer == 1) { page = "reference"; render() }
                action("本步帮助 · 离线说明") { openCoach() }
                action("返回") { finish() }
            }
            "reference" -> {
                label("图解候选 · 先认件，再看接合关系",22f)
                label("下图用于核对两种形状和最终配对关系，尚未经过实物核准。它不表示插入运动轨迹，也不定义标签精确贴位。",16f)
                guideImage("purple-actual-faces.png","两种半片形状及紫色试点编号")
                guideImage("pair-reference.png","依据源模型的最终配对关系候选")
                action("返回项目") { page = "home"; render() }
            }
            "labels" -> {
                val id = 4 + (session.layer-1)*4
                root.addView(ImageView(this).apply {
                    adjustViewBounds = true
                    contentDescription = "源模型形状参考：左为半片1，右为半片2；不是安装姿态图"
                    setImageBitmap(assets.open(if(session.layer==1) "shelf-guide/purple-actual-faces.png" else "shelf-half-reference.png").use { android.graphics.BitmapFactory.decodeStream(it) })
                },LinearLayout.LayoutParams(-1,-2))
                label("本层文件：层${session.layer}板-1 和 层${session.layer}板-2。请按打印材料中的形状参考核对，不能凭左右位置猜。")
                label("上图只帮助区分两种形状；图中编号属于紫色试点，其余层以本页下方编号为准。",15f)
                if (session.layer == 1) label("紫色实物同面双码：半片1为 004、005，半片2为 006、007。拍摄时让四枚码和中间接头同时入镜。") else label("半片1：A面$id，B面${id+1}\n半片2：A面${id+2}，B面${id+3}\nA/B只是两侧的标签登记名，层板两面都允许安装。")
                if (session.layer != 1) label("用纸质黑白码临时贴在平面上，不覆盖接头。只验证身份，不代表已登记精确贴位。程序无法自动发现贴错标签。")
                action("我已核对本层两半和标签编号") {
                    if(prefs.edit().putBoolean("binding_v2_${session.contentVersion}_${session.layer}",true).commit()) { page = "find"; notice = "贴标由你核对；接下来使用相机扫码。" }; render()
                }
                action("返回项目") { page = "home"; render() }
            }
            "find" -> {
                label("1 / 3 · 找齐本层两半",22f)
                label("可逐个读取，也可同框读取。两半只能来自当前层；看到标签不会自动确认装好。")
                label("已找到：" + if(session.seenRoles.isEmpty()) "暂无" else session.seenRoles.sorted().joinToString("、") { "半片$it" })
                label(if(screenOnly && session.readyForGuidance) "本层两半已找到，可继续检查说明和测试保存。" else session.message)
                val bound = screenOnly || prefs.getBoolean("binding_v2_${session.contentVersion}_${session.layer}",false)
                if (!bound) action("先核对标签与实物") { page = "labels"; render() }
                action("打开相机读取标签",bound && !session.confirmed) {
                    scanning = true
                    scanner.launch(Intent(this,ShelfScanActivity::class.java).putExtra("purple_freeze",!screenOnly && session.layer==1).putExtra("prepare_automatic_test",intent.getBooleanExtra("prepare_automatic_test",false)))
                }
                action("查看本步指导",session.readyForGuidance) { page = "guide"; render() }
                action("清除本轮找件结果") { session = session.invalidate(); render() }
                action("返回项目") { session = session.invalidate(); page = "home"; render() }
            }
            "guide" -> {
                label(if(screenOnly) "2 / 3 · 检查说明页面，不操作实物" else "2 / 3 · 放下手机，双手装配",22f)
                label("① 核对两半同为${ShelfRules.colorName(session.layer)}层，找到相互对应的榫和槽。\n\n② 先摆正、对齐接头，再按实物装配姿势向下直插。无需滑入、倾斜或在插入时旋转。\n\n③ 彩色层板允许A/B面混搭，但缺口方向仍须与图示一致。若卡住，退回检查接头对齐，不强压。")
                label("依据：你已确认的直插方式。这里是文字指导；尚未给出精确槽口箭头或经实物核准的动画。",15f)
                action("本步帮助 · 离线说明") { openCoach() }
                val check = CheckBox(this).apply { text = if(screenOnly) "仅测试确认保存，不代表实际装配" else "我已实际完成本层两半的装配"; root.addView(this) }
                val confirm = action(if(screenOnly) "保存测试确认" else "确认完成并保存",false) {
                    if(save(session.confirm())) { page = "progress"; notice = if(screenOnly) "已保存屏幕测试确认。" else "已保存你的完成确认。" }; render()
                }
                check.setOnCheckedChangeListener { _, checked -> confirm.isEnabled = checked && session.readyForGuidance }
                action("不确定 / 重新找件") { session = session.invalidate(); page = "find"; render() }
            }
            "sideGuide" -> {
                label("紫层与侧框 · 选槽",22f)
                guideImage("side-frame-purple.png","模型左侧框正视图，底排左右两个槽用紫框标出。")
                label("先辨认架子的底部，再找最下排同高度的两个槽。图按模型左侧框绘制；实物翻转时，仍以装配后的底部为准。")
                action("回到单侧试装") { page="sideFit"; render() }
            }
            "sideFit" -> sideFitPage()
            "progress" -> {
                label("3 / 3 · 本轮进度",22f)
                if(screenOnly) label("这是屏幕流程验证记录，不是实物完成证明。")
                label(if(session.confirmed && screenOnly) "屏幕测试确认已保存；退出后可恢复，真实装配记录未变。" else if(session.confirmed) "已确认：${ShelfRules.colorName(session.layer)}层的两半预装。\n来源：用户手动确认，不是相机自动验收。\n退出后可恢复此记录。" else "尚无完成确认。")
                if (!screenOnly && session.confirmed && session.layer == 1) {
                    val key = "side_fit_v1_${session.runId}"
                    prefs.getString("${key}_fit",null)?.let {
                        label("最近一次单侧试装 · $it · ${prefs.getString("${key}_visibility","")}",16f)
                    }
                    action("下一步 · 紫层与侧框试装") { page="sideFit"; render() }
                }
                action("撤销本层完成",session.confirmed) {
                    AlertDialog.Builder(this).setMessage("撤销本层完成，并清除找件记录？")
                        .setPositiveButton("撤销") { _,_ -> if(save(session.undo())) { notice = "已撤销；请重新找件。"; page = "find" }; render() }
                        .setNegativeButton("取消",null).show()
                }
                action("返回项目") { page = "home"; render() }
            }
        }
    }
}
