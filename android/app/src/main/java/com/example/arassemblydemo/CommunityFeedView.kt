package com.example.arassemblydemo

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.DateFormat
import java.util.Date
import java.util.WeakHashMap

internal class CommunityViews(val context: Context) {
    val ink = 0xff2d382b.toInt()
    val muted = 0xff616e60.toInt()
    val paper = 0xfffaf9f3.toInt()
    val pale = 0xffe8edde.toInt()
    val accent = 0xff365240.toInt()
    fun d(size: Int) = (size * context.resources.displayMetrics.density + .5f).toInt()
    fun str(id: Int, vararg args: Any): String = context.getString(id, *args)
    fun bg(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = d(radius).toFloat() }
    fun label(value: String, size: Int, bold: Boolean = false, color: Int = ink) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    fun action(name: String, click: () -> Unit) = label(name, 14, true, accent).apply {
        gravity = Gravity.CENTER; minHeight = d(44); isFocusable = true; setPadding(d(12),0,d(12),0)
        background = bg(pale,16); setOnClickListener { click() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
            }
        }
    }
    fun icon(symbol: String, textId: Int, click: () -> Unit) = PortfolioIconView(context, symbol, accent).apply {
        background = bg(pale,22); contentDescription = str(textId); isFocusable = true
        setOnClickListener { click() }
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun moderationMark(keep:Boolean, active:Boolean, label:Int, click:()->Unit) = FrameLayout(context).apply {
        isFocusable=true;isSelected=active;contentDescription=str(label);setOnClickListener {click()}
        val tint=if(keep) accent else 0xff8a5350.toInt()
        addView(label(if(keep)"✓" else "×",17,true,if(active) Color.WHITE else tint).apply {
            gravity=Gravity.CENTER;includeFontPadding=false
            background=bg(if(active) accent else if(keep) 0xfff1f6e8.toInt() else 0xfff9ede6.toInt(),12)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },FrameLayout.LayoutParams(d(27),d(27),Gravity.CENTER))
    }
    fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = d(top) }
    fun weighted() = LinearLayout.LayoutParams(0,-2,1f)
    fun date(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, AssemblyLanguage.locale(context)).format(Date(at))
    fun title(p: CommunityPost) = if (AssemblyLanguage.english(context)) p.titleEn else p.titleZh
    fun body(p: CommunityPost) = if (AssemblyLanguage.english(context)) p.bodyEn else p.bodyZh
    fun surface(post: CommunityPost): Int = PortfolioIdea.byKey[post.templateKey]?.surface ?:
        listOf(0xffe3ebe2,0xffefe3d6,0xffe2e9e9,0xffeae4ed,0xffe9e8da)[Math.floorMod(post.id.hashCode(),5)].toInt()
    fun model(post: CommunityPost, animate: Boolean): View {
        val idea = PortfolioIdea.byKey[post.templateKey]
        return when {
            post.model != null -> CommunityModelView(context, post.model, animate)
            idea != null -> PortfolioConceptView(context, idea, animate)
            else -> View(context)
        }
    }
}

/** Recyclable four-card spreads have staggered gutters. Only visible spreads own model views. */
internal class CommunityFeedView(
    context: Context,
    private val onOpen: (String) -> Unit,
    private val header: View? = null
) : LinearLayout(context) {
    private val ui = CommunityViews(context)
    private val catalog = CommunityCatalog.get(context)
    private val activity = PortfolioActivityStore(context)
    private val list = ListView(context)
    private val ids = ArrayList<String>()
    private val seen = HashSet<String>()
    private val cache = android.util.LruCache<String,CommunityPost>(64)
    private var offset = 0
    private var revision = catalog.status().revision
    private var showTools = catalog.status().debug
    private var keptDesigns:Set<String> = emptySet()
    private var loading = false
    private var generation = 0
    private val restoring = mutableSetOf<String>()
    private val cards = CardAdapter()
    private val sessionKey = (if(header==null) "community:" else "profile:") + (LocalAccounts(context).current()?.id ?: "guest")
    private val changed: () -> Unit = {
        val s = catalog.status()
        if(revision != s.revision) reconcile()
        val retained=if(s.debug)catalog.retained().filterNot {it.sample}.mapTo(hashSetOf()){it.postId} else emptySet()
        if(showTools != s.debug || keptDesigns!=retained) { showTools=s.debug;keptDesigns=retained;cards.notifyDataSetChanged() }
    }

    init {
        orientation = VERTICAL
        if (header == null) {
            val bar = LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            bar.addView(ui.label(ui.str(R.string.community_h1),25,true),ui.weighted())
            bar.addView(ui.action("↻",::refresh).apply { contentDescription = ui.str(R.string.community_refresh); textSize = 23f },LayoutParams(ui.d(44),ui.d(44)))
            bar.addView(ui.icon("tools", R.string.community_manage) { CommunityManagement.show(context as Activity) },
                LayoutParams(ui.d(44),ui.d(44)).apply { leftMargin=ui.d(8) })
            addView(bar,ui.lp())
            addView(ui.label(ui.str(R.string.community_sub),13,false,ui.muted),ui.lp(6))
        } else {
            list.addHeaderView(header,null,false)
            list.addHeaderView(ui.label(ui.str(R.string.community_feed),21,true).apply { setPadding(0,ui.d(16),0,ui.d(12)) },null,false)
        }
        list.divider = null; list.isVerticalScrollBarEnabled = false
        list.selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        list.clipToPadding = false
        list.setPadding(0,if(header == null) ui.d(12) else 0,0,ui.d(84))
        list.adapter = cards
        addView(list,LayoutParams(-1,0,1f))
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view:AbsListView?, state:Int) {}
            override fun onScroll(view:AbsListView?, first:Int, visible:Int,total:Int) {
                if(isAttachedToWindow && visible>0 && first+visible >= total-2 && ids.isNotEmpty()) appendPage()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val state = navigation[context]?.get(sessionKey)
        ids.clear(); seen.clear()
        if(state!=null){ ids.addAll(state.ids); seen.addAll(ids); state.visible.forEach {cache.put(it.id,it)};offset=state.offset; revision=state.revision }
        else { offset=0; revision=catalog.status().revision }
        if(revision!=catalog.status().revision) reconcile()
        if(ids.isEmpty()) appendPage()
        showTools=catalog.status().debug
        cards.notifyDataSetChanged()
        if (state!=null) list.setSelectionFromTop(state.position,state.top)
        catalog.addListener(changed)
        if(ids.isNotEmpty()) loader.execute {catalog.requestMore()}
    }
    override fun onDetachedFromWindow() {
        val start=((list.firstVisiblePosition-list.headerViewsCount)*4).coerceIn(0,ids.size)
        val visible=ids.subList(start,minOf(ids.size,start+20)).mapNotNull {cache.get(it)}
        navigation.getOrPut(context){ mutableMapOf() }[sessionKey]=Position(ids.toList(),visible,offset,list.firstVisiblePosition,
            (list.getChildAt(0)?.top ?: list.paddingTop) - list.paddingTop,revision)
        generation++;loading=false;restoring.clear()
        catalog.removeListener(changed)
        super.onDetachedFromWindow()
    }
    private fun refresh() {
        generation++;loading=false;restoring.clear()
        catalog.refresh(); ids.clear();seen.clear();cache.evictAll();offset=0;appendPage()
        cards.notifyDataSetChanged(); list.setSelection(0)
    }
    private fun appendPage() {
        if(loading || !isAttachedToWindow)return
        loading=true
        val token=generation
        val start=offset
        loader.execute {
            var begin=start
            var next=catalog.page(begin,20)
            if(next.isEmpty() && begin>0){begin=0;next=catalog.page(0,20)}
            // Commit the local page before starting a network top-up.
            post {
                if(!isAttachedToWindow || token!=generation)return@post
                offset=begin+next.size;loading=false
                val before=ids.size
                for(p in next) if(seen.add(p.id)){ids.add(p.id);cache.put(p.id,p)}
                if(ids.size>before) cards.notifyDataSetChanged()
                else if(next.isNotEmpty()) appendPage()
            }
            catalog.requestMore()
        }
    }
    private fun restorePage(keys:List<String>) {
        val missing=keys.filter {cache.get(it)==null && restoring.add(it)}
        if(missing.isEmpty())return
        val token=generation
        loader.execute {
            val values=missing.mapNotNull(catalog::post)
            post {
                if(!isAttachedToWindow || generation!=token)return@post
                restoring.removeAll(missing.toSet());values.forEach {cache.put(it.id,it)}
                if(values.isNotEmpty())cards.notifyDataSetChanged()
            }
        }
    }
    private fun reconcile() {
        generation++;loading=false;restoring.clear()
        val first=list.firstVisiblePosition
        val firstGroup=(first-list.headerViewsCount).coerceAtLeast(0)
        val anchor=ids.getOrNull(firstGroup*4)
        val y=(list.getChildAt(0)?.top ?: list.paddingTop)-list.paddingTop
        val hidden=catalog.moderation().filterNot { it.sample }.mapTo(hashSetOf()) { it.postId }
        ids.removeAll { it in hidden || (it !in PortfolioIdea.byKey && !it.startsWith("study-") && catalog.post(it)==null) }
        seen.clear();seen.addAll(ids);revision=catalog.status().revision
        // page() caches the shared prefix; counting from the survivors can skip a newly restored item.
        offset=0
        if(ids.isEmpty()) appendPage()
        cards.notifyDataSetChanged()
        val index=ids.indexOf(anchor)
        if(index>=0 && first>=list.headerViewsCount)list.setSelectionFromTop(list.headerViewsCount+index/4,y)
    }
    private inner class CardAdapter : BaseAdapter() {
        override fun getCount()=(ids.size+3)/4
        override fun getItem(position:Int)=ids.subList(position*4,minOf(ids.size,position*4+4))
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int, convertView:View?, parent:ViewGroup):View {
            val row = (convertView as? LinearLayout ?: LinearLayout(context))
            row.removeAllViews()
            val slice = getItem(position)
            restorePage(slice)
            repeat(2) { side ->
                val col=ui.column()
                row.addView(col,ui.weighted().apply { if(side==0) rightMargin=ui.d(5) else leftMargin=ui.d(5) })
                for (i in side until slice.size step 2) {
                    val tall=(i==0 || i==3) == (position%2==0)
                    val h=if(tall) 142 else 108
                    val design=cache.get(slice[i])
                    val tile=design?.let {card(it,h)} ?: View(context).apply {minimumHeight=ui.d(h+58);background=ui.bg(ui.pale,19)}
                    col.addView(tile,ui.lp().apply { bottomMargin=ui.d(10) })
                }
            }
            return row
        }
    }
    private fun card(p:CommunityPost, height:Int):View {
        val box = ui.column().apply {
            background = ui.bg(ui.surface(p),19); clipToOutline=true; isFocusable=true
            setOnClickListener { onOpen(p.id) }
        }
        val top = FrameLayout(context)
        top.addView(ui.model(p,false), FrameLayout.LayoutParams(-1,-1))
        if(showTools) {
            val kept=catalog.isKept(p.id)
            val marks=LinearLayout(context)
            marks.addView(ui.moderationMark(true,kept,if(kept)R.string.community_release_design else R.string.community_keep_design) {
                catalog.keep(p.id,!kept)
            },LayoutParams(ui.d(40),ui.d(44)))
            marks.addView(ui.moderationMark(false,false,R.string.community_hide_post){
                CommunityManagement.hidePost(context as Activity,p)
            },LayoutParams(ui.d(40),ui.d(44)))
            top.addView(marks, FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        }
        box.addView(top,LayoutParams(-1,ui.d(height)))
        val text=ui.column().apply { setPadding(ui.d(11),0,ui.d(8),ui.d(11)) }
        text.addView(ui.label(ui.title(p),16,true).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        text.addView(ui.label(ui.str(R.string.community_likes,p.sampleLikes + activity.localLikeCount(p.id)),12,false,ui.muted),ui.lp(4))
        box.addView(text)
        return box
    }
    private data class Position(val ids:List<String>,val visible:List<CommunityPost>,val offset:Int,val position:Int,val top:Int,val revision:Long)
    private companion object {
        val navigation=WeakHashMap<Context,MutableMap<String,Position>>()
        val loader=java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}

