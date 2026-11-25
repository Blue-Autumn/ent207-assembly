package com.example.arassemblydemo

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton

/** The outer catalog owns presentation and favorites only; assembly owns all task progress. */
class MainActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))

    private enum class Page { WORKS, COMMUNITY, DETAIL, MY }
    private var page = Page.WORKS
    private var detailFrom = Page.WORKS
    private var selectedWork = "shelf"
    private var clickCount = 0
    private var launchingActivity = false
    private lateinit var root: LinearLayout
    private lateinit var shell: FrameLayout
    private var rackPreview: PortfolioRackView? = null
    private var ideaPreview: PortfolioConceptView? = null
    private lateinit var social: PortfolioActivityStore
    private var previousTab: Int? = null
    private val scrollPositions = mutableMapOf<Page, Int>()
    private var visibleScroll: Pair<Page,ScrollView>? = null
    private var transitioning = false
    private var renderGeneration = 0
    private val ink = Color.rgb(39, 43, 33)
    private val muted = Color.rgb(117, 124, 111)
    private val paper = Color.rgb(250, 249, 243)
    private val yellow = Color.rgb(238, 239, 181)
    private val soft = Color.rgb(239, 240, 232)
    private val concepts = PortfolioIdea.byKey.keys
    private fun word(id: Int, vararg args: Any): String = getString(id, *args)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightSystemBars.configure(window)
        page = runCatching { Page.valueOf(savedInstanceState?.getString("portfolio.page").orEmpty()) }.getOrDefault(Page.WORKS)
        detailFrom = runCatching { Page.valueOf(savedInstanceState?.getString("portfolio.from").orEmpty()) }.getOrDefault(Page.WORKS)
        selectedWork = savedInstanceState?.getString("portfolio.work")?.takeIf { it in concepts || it == "shelf" } ?: "shelf"
        clickCount = savedInstanceState?.getInt(STATE_COUNT) ?: 0
        for (tab in listOf(Page.WORKS,Page.MY)) scrollPositions[tab] = savedInstanceState?.getInt("scroll.${tab.name}") ?: 0
    }

    override fun onResume() { super.onResume(); launchingActivity = false; render() }
    override fun onPause() { rackPreview?.pauseShowcase(); ideaPreview?.stopMotion(); super.onPause() }
    override fun onDestroy() { disposePreviews(); super.onDestroy() }
    private fun disposePreviews() { rackPreview?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; rackPreview = null }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (page) {
            Page.DETAIL -> { page = detailFrom; render() }
            Page.MY, Page.COMMUNITY -> switchTab(0)
            Page.WORKS -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("portfolio.page", page.name)
        outState.putString("portfolio.from", detailFrom.name)
        outState.putString("portfolio.work", selectedWork)
        outState.putInt(STATE_COUNT, clickCount)
        rememberScroll()
        for ((tab,y) in scrollPositions) outState.putInt("scroll.${tab.name}",y)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        renderGeneration++; transitioning=false
        social = PortfolioActivityStore(this)
        rememberScroll()
        visibleScroll = null
        ideaPreview = null
        rackPreview?.let { it.animate().cancel(); it.translationX=0f; it.translationY=0f; it.scaleX=1f; it.scaleY=1f; (it.parent as? ViewGroup)?.removeView(it); it.pauseShowcase() }
        shell = FrameLayout(this).apply { setBackgroundColor(paper); clipToPadding = false }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shell.addView(root, FrameLayout.LayoutParams(-1,-1))
        LightSystemBars.insetContent(shell, dp(22), dp(8), dp(22), dp(10))
        shell.setBackgroundColor(paper)
        when (page) {
            Page.WORKS -> works()
            Page.DETAIL -> detail()
            Page.MY -> profile()
            Page.COMMUNITY -> {
                val feed = CommunityFeedView(this, onOpen = { id ->
                    launchActivity(CommunityDetailActivity.intent(this, id))
                })
                root.addView(feed, LinearLayout.LayoutParams(-1, 0, 1f))
                navigation()
            }
        }
        setContentView(shell)
        shell.requestApplyInsets()
    }

    private fun heading(title: String, back: Boolean = false) {
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (back) header.addView(icon("back", word(R.string.portfolio_back)) { onBackPressed() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(text(title, if (back) 15f else 23f).apply { if (!back) setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, row(0))
    }

    private fun scroll(): LinearLayout {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, dp(if (page == Page.DETAIL) 18 else 84)) }
        val scroll = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        visibleScroll = page to scroll
        val y = scrollPositions[page] ?: 0
        if (y > 0) scroll.post { scroll.scrollTo(0,y) }
        return body
    }
    private fun rememberScroll() {
        visibleScroll?.let { (tab,scroll) -> if (tab != Page.DETAIL) scrollPositions[tab] = scroll.scrollY }
    }

    private fun progressLabel(): String {
        val snapshot = AssemblyProgressSummary.read(this)
        return if (snapshot == null) word(R.string.portfolio_pending_summary)
            else word(R.string.portfolio_progress_count, snapshot.done, snapshot.total)
    }

    private fun works() {
        heading(word(R.string.portfolio_logo))
        val body = scroll()
        body.addView(text(word(R.string.portfolio_intro), 30f).apply { setTypeface(typeface, Typeface.BOLD); setLineSpacing(dp(3).toFloat(), 1f) }, row(0))
        body.addView(text(word(R.string.portfolio_intro_sub), 14f, muted), row(dp(10)))
        body.addView(shelfCard(), row(dp(16)))
        body.addView(text(word(R.string.project_saved_works), 21f), row(dp(28)))
        val catalog = CommunityCatalog.get(this)
        val saved = social.savedIds().filterNot { it == "shelf" || catalog.isHidden(it) }.toSet()
        if (saved.isEmpty()) {
            body.addView(text(word(R.string.project_save_hint), 14f, muted), row(dp(7)))
            body.addView(action(word(R.string.project_find_works), false) { switchTab(1) }, row(dp(6)))
        } else {
            val ordered = concepts.filter { it in saved } + (saved - concepts)
            body.addView(conceptGrid(ordered), row(dp(14)))
        }
        navigation()
    }

    private fun conceptGrid(keys: Collection<String>): View {
        val cards = LinearLayout(this)
        val columns = List(2) { column -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            cards.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply { if(column == 0) rightMargin=dp(7) else leftMargin=dp(7) })
        } }
        keys.forEachIndexed { index, id -> columns[index%2].addView(conceptCard(id, if(index%3==0) 150 else 126), row(if(index<2) 0 else dp(14))) }
        return cards
    }

    private fun shelfCard(): View {
        val card = FrameLayout(this).apply { minimumHeight = dp(388); contentDescription = word(R.string.portfolio_open_work, word(R.string.portfolio_shelf)); isClickable = true; setOnClickListener { openDetail("shelf") } }
        card.addView(View(this).apply { background = rounded(yellow, 28) }, FrameLayout.LayoutParams(-1, dp(316), Gravity.BOTTOM))
        val preview = obtainRack(false).also { it.clickablePreview { openDetail("shelf") } }
        card.addView(preview, FrameLayout.LayoutParams(-1, dp(282), Gravity.TOP))
        val snapshot = AssemblyProgressSummary.read(this)
        val caption = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(55), dp(22)) }
        caption.addView(text(word(R.string.portfolio_shelf), 27f))
        caption.addView(text(if (snapshot == null) word(R.string.portfolio_open_hint) else progressLabel(), 12f, muted), row(dp(6)))
        card.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        card.addView(icon("arrow", word(R.string.portfolio_open_work, word(R.string.portfolio_shelf)), true) { openDetail("shelf") }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(16); bottomMargin = dp(28) })
        return card
    }

    private fun conceptCard(id: String, modelHeight: Int = 126): View {
        val idea = PortfolioIdea.byKey[id]
        val post = if (idea == null) social.post(id) else null
        val title = idea?.let { word(it.label) } ?: post?.let { if (AssemblyLanguage.english(this)) it.titleEn else it.titleZh }
            ?: return View(this).apply { visibility = View.GONE }
        val image = when {
            idea != null -> PortfolioConceptView(this, idea)
            post?.model != null -> CommunityModelView(this, post.model)
            else -> PortfolioIdea.byKey[post?.templateKey]?.let { PortfolioConceptView(this,it) } ?: View(this)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(idea?.surface ?: 0xffe8eee4.toInt(), 22); setPadding(dp(10), dp(8), dp(10), dp(14)); isFocusable = true; contentDescription = word(R.string.portfolio_open_work, title); setOnClickListener { openDetail(id,image) } }
        box.addView(image, LinearLayout.LayoutParams(-1, dp(modelHeight)))
        box.addView(text(title, 15f))
        return box
    }

    private fun obtainRack(interactive: Boolean): PortfolioRackView = (rackPreview ?: PortfolioRackView(this, interactive).also { rackPreview = it }).also {
        it.setInteractive(interactive); it.resumeShowcase()
    }
    private fun openDetail(work: String, origin: View? = null) {
        if(transitioning) return
        if (work != "shelf" && work !in concepts) {
            launchActivity(CommunityDetailActivity.intent(this, work))
            return
        }
        if (work == "shelf") social.recordShelfView() else social.post(work)?.let(social::recordView)
        val from = (origin ?: rackPreview?.takeIf { work == "shelf" && page == Page.WORKS })?.let { view ->
            val xy=IntArray(2); view.getLocationOnScreen(xy); Rect(xy[0],xy[1],xy[0]+view.width,xy[1]+view.height)
        }
        detailFrom = if (page == Page.DETAIL) detailFrom else page; selectedWork = work; page = Page.DETAIL; render()
        if(from != null && from.width()>0 && from.height()>0 && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            transitioning = true
            val generation = renderGeneration
            (if (work == "shelf") rackPreview else ideaPreview)?.let { model ->
                model.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        model.viewTreeObserver.removeOnPreDrawListener(this)
                        if(generation != renderGeneration || page != Page.DETAIL) return true
                        val xy=IntArray(2); model.getLocationOnScreen(xy)
                        model.pivotX=model.width/2f; model.pivotY=model.height/2f
                        model.translationX=from.exactCenterX()-(xy[0]+model.width/2f)
                        model.translationY=from.exactCenterY()-(xy[1]+model.height/2f)
                        // The renderer uses a fixed azimuth-independent fit; preserve the aspect of the mesh.
                        val scale = minOf(from.width().toFloat()/model.width.coerceAtLeast(1), from.height().toFloat()/model.height.coerceAtLeast(1))
                        model.scaleX=scale; model.scaleY=scale
                        (model.parent as? ViewGroup)?.clipChildren=false
                        model.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(480)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .withEndAction { transitioning=false }.start()
                        return true
                    }
                })
            }
        }
    }

    private fun detail() {
        heading(word(R.string.portfolio_preview), true)
        val body = scroll()
        if (selectedWork == "shelf") {
            val preview = obtainRack(true)
            body.addView(preview, LinearLayout.LayoutParams(-1, dp(330)))
            body.addView(text(word(R.string.portfolio_shelf), 32f).apply { setTypeface(typeface, Typeface.BOLD) }, row(dp(24)))
            val snapshot = AssemblyProgressSummary.read(this)
            if (snapshot != null) {
                body.addView(text(progressLabel(), 14f), row(dp(18)))
                body.addView(PortfolioProgressView(this, snapshot.done, snapshot.total), LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
            }
            val start = when { snapshot == null -> R.string.portfolio_continue; snapshot.done == snapshot.total -> R.string.portfolio_revisit; snapshot.done == 0 -> R.string.portfolio_begin; else -> R.string.portfolio_continue }
            root.addView(action(word(start), true) { openGuide() }, row(dp(10)))
            root.addView(action(word(R.string.portfolio_route), false) { launchActivity(Intent(this,VirtualAssemblyActivity::class.java)) }, row(dp(2)))
        } else {
            val idea = PortfolioIdea.byKey.getValue(selectedWork)
            val model = PortfolioConceptView(this,idea,true).apply { background = rounded(idea.surface, 24) }
            ideaPreview = model
            val heroHeight = (resources.configuration.screenHeightDp * .48f).toInt().coerceIn(220, 432)
            body.addView(model, LinearLayout.LayoutParams(-1, dp(heroHeight)))
            body.addView(text(word(idea.label), 32f).apply { setTypeface(typeface, Typeface.BOLD) }, row(dp(22)))
            val story = idea.story()
            val post = social.post(idea.key)
            val tags = LinearLayout(this)
            listOf(story.subjectTag, story.detailTag).forEach { title ->
                val chip = text(word(title), 12f, ink).apply {
                    background = rounded(idea.surface, 16)
                    setPadding(dp(11), dp(4), dp(11), dp(4))
                }
                tags.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(7) })
            }
            body.addView(tags, row(dp(9)))
            val description = post?.let { if (AssemblyLanguage.english(this)) it.bodyEn else it.bodyZh } ?: word(story.description)
            body.addView(text(description, 17f, 0xff4d5548.toInt()).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            }, row(dp(17)))
            body.addView(View(this).apply { setBackgroundColor(0xffdce0d3.toInt()) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(22) })
            val reactions = LinearLayout(this)
            val discussion = CommunityDiscussionView(this, idea.key)
            if (post != null) {
                reactions.addView(reaction(post, false, discussion::refresh), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                reactions.addView(reaction(post, true, discussion::refresh), LinearLayout.LayoutParams(-2, -2))
            }
            body.addView(reactions, row(dp(12)))
            body.addView(discussion, row(dp(18)))
        }
    }

    private fun reaction(post: CommunityPost, save: Boolean, changed: () -> Unit): ToggleButton {
        val off = word(if (save) R.string.portfolio_favorite else R.string.idea_like)
        val on = word(if (save) R.string.portfolio_favorited else R.string.idea_liked)
        return ToggleButton(this).apply {
            textOff = off
            textOn = on
            isChecked = if (save) social.isSaved(post.id) else social.isLiked(post.id)
            text = if (isChecked) on else off
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(92)
            setPadding(dp(15), dp(7), dp(15), dp(7))
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (save) R.drawable.portfolio_bookmark else R.drawable.portfolio_heart, 0, 0, 0)
            fun paintState() {
                val tint = if (!isChecked) soft else if (save) 0xffdce9d9.toInt() else 0xfff1ddda.toInt()
                val glyph = if (isChecked && !save) 0xff92524f.toInt() else ink
                setTextColor(glyph)
                compoundDrawableTintList = ColorStateList.valueOf(glyph)
                background = RippleDrawable(ColorStateList.valueOf(0x18414d3a), rounded(tint, 30), null)
                stateListAnimator = null
            }
            paintState()
            setOnClickListener {
                try {
                    if (save) social.setSaved(post, isChecked) else social.setLiked(post, isChecked)
                    changed()
                } catch (_: IllegalStateException) {
                    isChecked = !isChecked
                    Toast.makeText(this@MainActivity, R.string.idea_reaction_failed, Toast.LENGTH_SHORT).show()
                }
                paintState()
            }
        }
    }

    private fun profile() {
        heading(word(R.string.portfolio_my))
        val store = PortfolioActivityStore(this)
        val body = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(0,dp(10),0,dp(8))
        }
        val shortcuts=LinearLayout(this).apply {
            background=rounded(0xffeef0e6.toInt(),20)
            gravity=Gravity.CENTER; setPadding(dp(3),dp(4),dp(3),dp(4))
        }
        val shelves=listOf(
            LibraryShelf(R.string.library_saved,R.string.library_empty_saved,"saved",store.savedIds().toList()),
            LibraryShelf(R.string.library_liked,R.string.library_empty_liked,"heart",store.likedIds().toList()),
            LibraryShelf(R.string.library_history,R.string.library_empty_history,"history",store.history()),
            LibraryShelf(R.string.library_comments,R.string.library_empty_comments,"comment",store.myComments().map { it.postId })
        )
        for (shelf in shelves) {
            val count=shelf.ids.count { !CommunityCatalog.get(this).isHidden(it) }
            val shortcut=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
                isFocusable=true; minimumHeight=dp(65)
                contentDescription=word(R.string.library_shortcut_count,word(shelf.label),count)
                setOnClickListener { openLibrary(shelf,store) }
            }
            val tally=LinearLayout(this).apply { gravity=Gravity.CENTER }
            tally.addView(PortfolioIconView(this,shelf.icon,0xff42583e.toInt()),LinearLayout.LayoutParams(dp(36),dp(36)))
            tally.addView(text(count.toString(),12f,muted))
            shortcut.addView(tally)
            shortcut.addView(text(word(shelf.label), 12f).apply { gravity=Gravity.CENTER; maxLines=1 })
            shortcuts.addView(shortcut,LinearLayout.LayoutParams(0,-2,1f))
        }
        val account = LocalAccounts(this).current()
        val accountCard = FrameLayout(this).apply {
            background = rounded(0xffe0e8d9.toInt(),24)
            clipToOutline = true
        }
        val watermark = PortfolioIconView(this,"person",0xffc5d3bd.toInt()).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        accountCard.addView(watermark,FrameLayout.LayoutParams(dp(270),dp(270),Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = -dp(74); bottomMargin = -dp(84)
        })
        val accountColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),dp(18),dp(20),dp(18))
        }
        accountColumn.addView(text(account?.name ?: word(R.string.portfolio_guest),28f).apply { setTypeface(typeface,Typeface.BOLD) })
        accountColumn.addView(text(word(if (account == null) R.string.portfolio_guest_copy else R.string.portfolio_account_copy),14f,0xff485741.toInt()),row(dp(5)))
        accountColumn.addView(action(word(if (account == null) R.string.portfolio_login else R.string.portfolio_account),true) {
            launchActivity(Intent(this,LocalAccountActivity::class.java))
        }.apply { minHeight=dp(44); minimumHeight=dp(44); textSize=14f; setPadding(dp(18),dp(6),dp(18),dp(6)) },
            LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(14) })
        accountCard.addView(accountColumn,FrameLayout.LayoutParams(-1,-2))
        body.addView(accountCard,row(0))
        body.addView(shortcuts,row(dp(14)))
        sectionTitle(body,R.string.portfolio_my_assembly)
        val project = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(13),dp(16),dp(13)); background=rounded(soft,18)
            isFocusable=true; setOnClickListener { openGuide(true) }
        }
        project.addView(text(word(R.string.portfolio_shelf),16f))
        project.addView(text(progressLabel(),13f,muted),row(dp(2)))
        body.addView(project,row(dp(8)))
        sectionTitle(body,R.string.portfolio_preferences)
        val settings = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; background=rounded(0xffeeeeE5.toInt(),18); clipToOutline=true
        }
        body.addView(settings,row(dp(8)))
        preferenceRow(settings,"language",R.string.portfolio_language) {
            val labels = arrayOf(word(R.string.language_system), "简体中文", "English")
            AlertDialog.Builder(this).setTitle(R.string.language_title)
                .setSingleChoiceItems(labels, AssemblyLanguage.choices.indexOf(AssemblyLanguage.preference(this))) { dialog, index ->
                    dialog.dismiss()
                    val tag = AssemblyLanguage.choices[index]
                    if (tag != AssemblyLanguage.preference(this)) {
                        AssemblyLanguage.select(this, tag)
                        recreate()
                    }
                }.show()
        }
        preferenceRow(settings,"voice",R.string.portfolio_voice) { launchActivity(Intent(this,AssemblyVoiceActivity::class.java)) }
        preferenceRow(settings,"community",R.string.community_manage) { CommunityManagement.show(this) }
        preferenceRow(settings,"tools",R.string.portfolio_tools) { showTools() }
        root.addView(CommunityFeedView(this,onOpen={ openDetail(it) },header=body),LinearLayout.LayoutParams(-1,0,1f))
        navigation()
    }

    private data class LibraryShelf(val label:Int,val empty:Int,val icon:String,val ids:List<String>)

    private fun openLibrary(shelf:LibraryShelf,store:PortfolioActivityStore) {
        val notes=if(shelf.icon=="comment") store.myComments() else emptyList()
        val entries=shelf.ids.mapIndexedNotNull { index,id ->
            val title=if(id=="shelf") word(R.string.portfolio_shelf) else {
                val post=store.post(id) ?: return@mapIndexedNotNull null
                if(AssemblyLanguage.english(this)) post.titleEn else post.titleZh
            }
            id to (notes.getOrNull(index)?.let { title+"\n"+it.text } ?: title)
        }
        val dialog=AlertDialog.Builder(this).setTitle(shelf.label)
        if(entries.isEmpty()) dialog.setMessage(shelf.empty)
        else dialog.setItems(entries.map { it.second }.toTypedArray()) { _,index -> openDetail(entries[index].first) }
        dialog.setNegativeButton(R.string.common_close,null).show()
    }

    private fun sectionTitle(body: LinearLayout, title: Int) {
        body.addView(text(word(title),17f).apply { setTypeface(typeface,Typeface.BOLD) },row(dp(22)))
    }

    private fun preferenceRow(group: LinearLayout, symbol: String, title: Int, click: () -> Unit) {
        if (group.childCount > 0) group.addView(View(this).apply { setBackgroundColor(0xffd9ddcf.toInt()) },
            LinearLayout.LayoutParams(-1,dp(1).coerceAtLeast(1)).apply { leftMargin=dp(45); rightMargin=dp(12) })
        val item = LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(48); setPadding(dp(8),dp(2),dp(8),dp(2))
            isFocusable=true; contentDescription=word(title); setOnClickListener { click() }
        }
        item.addView(PortfolioIconView(this,symbol,0xff5b6754.toInt()),LinearLayout.LayoutParams(dp(37),dp(37)))
        item.addView(text(word(title),15f),LinearLayout.LayoutParams(0,-2,1f))
        item.addView(PortfolioIconView(this,"next",muted),LinearLayout.LayoutParams(dp(26),dp(26)))
        group.addView(item,row(0))
    }

    private fun launchActivity(intent: Intent) {
        if (launchingActivity) return
        launchingActivity = true
        startActivity(intent)
    }
    private fun openGuide(progress: Boolean = false) { launchActivity(Intent(this, AssemblyGuideActivity::class.java).putExtra("open_progress", progress).putExtra("resume_work", !progress)) }
    private fun navigation() {
        val index = tabs.indexOf(page)
        val bar = PortfolioTabBar(this,index,previousTab ?: index,::switchTab)
        previousTab = null
        val width = minOf(dp(350),resources.displayMetrics.widthPixels-dp(44)).coerceAtLeast(1)
        shell.addView(bar,FrameLayout.LayoutParams(width,dp(58),Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin=dp(3) })
    }
    private val tabs = listOf(Page.WORKS, Page.COMMUNITY, Page.MY)
    private fun switchTab(index:Int) {
        previousTab = tabs.indexOf(page).coerceAtLeast(0)
        page = tabs[index]
        render()
    }
    private fun action(label: String, filled: Boolean, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; isAllCaps = false; minWidth = 0; minHeight = dp(54)
        setPadding(dp(15), dp(12), dp(15), dp(12)); setTextColor(if (filled) Color.WHITE else ink)
        background = rounded(if (filled) ink else Color.TRANSPARENT, 27)
        stateListAnimator = null; setOnClickListener { click() }
    }
    private fun icon(symbol: String, label: String, filled: Boolean = false, click: () -> Unit) = PortfolioIconView(this, symbol, if(filled) Color.WHITE else ink).apply {
        contentDescription = label; background = rounded(if(filled) ink else Color.TRANSPARENT, 24); setOnClickListener { click() }
    }
    /** Historical and diagnostic routes remain available without competing with assembly. */
    private fun showTools() {
        val options: List<Pair<String, () -> Unit>> = listOf(
            getString(R.string.settings_network) to { startActivity(Intent(this, AiSettingsActivity::class.java)) },
            getString(R.string.tools_markers) to {
                startActivity(Intent(this, MarkerLiveTestActivity::class.java))
            },
            getString(R.string.tools_speech) to {
                startActivity(Intent(this, SpeechProbeActivity::class.java))
            },
            getString(R.string.tools_screen) to {
                startActivity(Intent(this, ShelfDemoActivity::class.java)
                    .putExtra("screen_validation", true).putExtra("prepare_automatic_test", true))
            },
            getString(R.string.tools_old_shelf) to {
                startActivity(Intent(this, ShelfDemoActivity::class.java))
            },
            getString(R.string.tools_old_steps) to {
                startActivity(Intent(this, AssemblyPlayerActivity::class.java))
            },
            getString(R.string.tools_old_checklist) to {
                startActivity(Intent(this, GuidanceActivity::class.java))
            },
            getString(R.string.tools_color) to {
                startActivity(Intent(this, PartProbeActivity::class.java))
            },
            getString(R.string.tools_ar) to {
                startActivity(Intent(this, NativeArActivity::class.java))
            },
            getString(R.string.enter_camera) to {
                startActivity(Intent(this, CameraTestActivity::class.java))
            },
            getString(R.string.tools_calibration) to {
                startActivity(Intent(this, CameraTestActivity::class.java).putExtra("calibration_sampling", true))
            },
            getString(R.string.tools_accuracy) to {
                startActivity(Intent(this, CameraTestActivity::class.java).putExtra("board_diagnostic", true))
            },
            getString(R.string.tools_buttons) to { showButtonCheck() },
        )
        AlertDialog.Builder(this).setTitle(getString(R.string.tools_list))
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(getString(R.string.common_close), null).show()
    }

    private fun showButtonCheck() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val result = text(
            if (clickCount > 0) getString(R.string.click_count, clickCount) else getString(R.string.hello_world), 20f
        ).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(result)
        content.addView(Button(this).apply {
            setText(R.string.test_button)
            isAllCaps = false
            setOnClickListener { clickCount++; result.text = getString(R.string.click_count, clickCount) }
        }, row(dp(12)))
        AlertDialog.Builder(this).setTitle(getString(R.string.environment_test))
            .setView(content).setPositiveButton(getString(R.string.common_close), null).show()
    }


    private fun text(value: String, size: Float, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun row(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val STATE_COUNT = "click_count" }
}
