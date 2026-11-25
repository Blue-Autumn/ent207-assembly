package com.example.arassemblydemo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*

/** This view is shared by the catalog's original ten concept pages and generated designs. */
internal class CommunityDiscussionView(context:Context, private val postId:String,
    private val catalog:CommunityCatalog=CommunityCatalog.get(context)) : LinearLayout(context) {
    private val ui=CommunityViews(context)
    private val store=PortfolioActivityStore(context)
    private val changed:()->Unit={refresh()}
    private var state: Discussion?=null
    private var shown=20
    private var more:View?=null
    private val rows=LinearLayout(context).apply { orientation=VERTICAL }
    private val rowViews=mutableListOf<View>()
    private val visibleBounds=android.graphics.Rect()
    private var readingDepth=0
    private var lastScrollY=0
    private var scroller:ScrollView?=null
    private val checkFrontier=ViewTreeObserver.OnScrollChangedListener {
        val y=scroller?.scrollY ?: 0
        val down=y>lastScrollY
        lastScrollY=y
        if(down && isShown && rows.getLocalVisibleRect(visibleBounds)){
            val reached=rowViews.indexOfLast { it.top+ui.d(14)<=visibleBounds.bottom }+1
            if(reached>=10 && reached>readingDepth){
                readingDepth=reached
                catalog.ensureComments(postId,readingDepth)
            }
            if(more!=null && rows.height-visibleBounds.bottom<ui.d(300)) {
                removeCallbacks(expand);postOnAnimation(expand)
            }
        }
    }
    private val expand=Runnable { if(isAttachedToWindow && more!=null)nextPage() }
    private data class Discussion(val post:CommunityPost, val comments:List<LocalPostComment>, val likes:Int,
        val debug:Boolean, val kept:Boolean, val retained:Set<String>, val generating:Boolean)

    private val metadata=ui.label("",14,true,ui.muted)
    init {
        orientation=VERTICAL
        catalog.openDiscussion(postId){store.post(postId)}
        catalog.ensureComments(postId)
        refresh()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); catalog.addListener(changed); refresh()
        scroller=generateSequence(parent){it.parent}.filterIsInstance<ScrollView>().firstOrNull()
        lastScrollY=scroller?.scrollY ?: 0
        viewTreeObserver.addOnScrollChangedListener(checkFrontier)
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(expand)
        scroller=null
        if(viewTreeObserver.isAlive)viewTreeObserver.removeOnScrollChangedListener(checkFrontier)
        catalog.removeListener(changed); super.onDetachedFromWindow()
    }
    private fun nextPage(){
        val current=state ?: return
        val available=current.comments.size+current.post.samples.size
        if(shown<available){shown+=40; renderComments(current)}
        else catalog.refreshComments(postId)
    }

    fun refresh() {
        if(catalog.isHidden(postId)){state=null;more=null;removeAllViews();return}
        val post = catalog.post(postId) ?: store.post(postId)
        if(post==null){state=null; more=null;removeAllViews(); return}
        val status=catalog.status()
        val retained=if(status.debug)catalog.keptReplyKeys(postId) else emptySet()
        val next=Discussion(post,store.visibleComments(postId),store.localLikeCount(postId),status.debug,catalog.isKept(postId),
            retained, catalog.commentBusy(postId))
        val old=state
        if(next==old)return
        state=next
        // Keep measured rows and scroll position when a new batch lands or starts loading.
        val append=old!=null && old.comments==next.comments && old.debug==next.debug &&
            old.retained==next.retained && next.post.samples.size>=old.post.samples.size &&
            next.post.samples.subList(0,old.post.samples.size)==old.post.samples
        if(append && next.post.samples.size>old!!.post.samples.size && readingDepth>=shown-10){
            shown=maxOf(shown,minOf(shown+40,next.comments.size+next.post.samples.size))
        }
        if(!append){
            rowViews.clear();rows.removeAllViews();removeAllViews()
            val title=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            title.addView(ui.label(ui.str(R.string.community_discussion),21,true),ui.weighted())
            title.addView(ui.action(ui.str(R.string.community_write)){ compose(state?.post ?: post) })
            addView(title,ui.lp(18))
            addView(metadata,ui.lp(8))
            addView(ui.label(ui.str(R.string.community_illustrative),12,false,ui.muted),ui.lp(5))
            addView(rows,ui.lp())
        }
        metadata.text=listOf(ui.str(R.string.community_likes,post.sampleLikes + next.likes),
            ui.str(R.string.community_reply_count,post.samples.size+next.comments.size)).joinToString("  ·  ")
        renderComments(next)
    }

    private fun renderComments(next:Discussion){
        val post=next.post
        val start=rowViews.size
        more=null
        val footer=indexOfChild(rows)+1
        if(footer<childCount)removeViews(footer,childCount-footer)
        for(i in start until minOf(shown,next.comments.size)){
            val comment=next.comments[i]
            val author=comment.authorName.ifEmpty { ui.str(R.string.portfolio_guest) }
            val mine=store.ownsComment(comment.id)
            addCommentRow(author,comment.text,comment.createdAt, if(mine) R.string.community_yours else R.string.community_local, R.string.community_delete,
                if(mine) ({ AlertDialog.Builder(context).setMessage(R.string.community_delete)
                    .setPositiveButton(R.string.community_delete){_,_ -> store.deleteComment(comment.id); refresh()}
                    .setNegativeButton(R.string.community_cancel,null).show(); Unit }) else null)
        }
        for(index in (start-next.comments.size).coerceAtLeast(0) until minOf(post.samples.size,shown-next.comments.size)){
            val sample=post.samples[index]
            val body=if(AssemblyLanguage.english(context))sample.textEn else sample.textZh
            val hide=if(next.debug) ({CommunityManagement.hideSample(context as Activity,post,index)}) else null
            val kept=next.debug && sample.key in next.retained
            val keep=if(next.debug) ({
                val current=catalog.post(postId)?.samples?.indexOfFirst {it.key==sample.key} ?: -1
                if(current>=0)catalog.keepSample(postId,current,!kept)
            }) else null
            addCommentRow(sample.author, body, sample.timestamp, R.string.community_sample, R.string.community_hide_sample, hide, kept, keep)
        }
        if(next.comments.size+post.samples.size>shown){
            more=ui.action(ui.str(R.string.community_more_comments),::nextPage)
            addView(more,ui.lp(8))
        }
        if(!next.generating && !next.debug && catalog.status().auto && next.comments.size+post.samples.size<=shown){
            addView(ui.action(ui.str(R.string.community_more_comments)){catalog.refreshComments(postId)},ui.lp(8))
        }
        if(next.generating)addView(ui.label(ui.str(R.string.community_new_replies),12,false,ui.muted),ui.lp(9))
        if(post.samples.isEmpty() && next.comments.isEmpty() && !next.generating){
            addView(ui.label(ui.str(R.string.community_empty_comments),15,false,ui.muted),ui.lp(14))
        }
        if(next.debug && catalog.post(postId)!=null) addView(ui.action(ui.str(R.string.community_add_comments)){
            catalog.refreshComments(postId)
            Toast.makeText(context,R.string.community_busy,Toast.LENGTH_SHORT).show()
        }.apply {isEnabled=!next.generating;alpha=if(isEnabled)1f else .55f},ui.lp(10))
        if(next.debug && catalog.post(postId)!=null) {
            val marks=LinearLayout(context).apply {gravity=Gravity.END}
            marks.addView(ui.moderationMark(true,next.kept,if(next.kept)R.string.community_release_design else R.string.community_keep_design) {
                catalog.keep(postId,!next.kept)
            },LayoutParams(ui.d(44),ui.d(44)))
            marks.addView(ui.moderationMark(false,false,R.string.community_hide_post) {
                CommunityManagement.hidePost(context as Activity,post)
            },LayoutParams(ui.d(44),ui.d(44)))
            addView(marks,ui.lp(6))
        }
    }

    private fun addCommentRow(author:String,body:String,time:Long,badge:Int,actionId:Int,manage:(()->Unit)?,
                              kept:Boolean=false,keep:(()->Unit)?=null) {
        rows.addView(View(context).apply { setBackgroundColor(0xffdfe3d7.toInt()) }, LayoutParams(-1,ui.d(1)).apply {topMargin=ui.d(14)})
        val row=LinearLayout(context).apply { gravity=Gravity.TOP }
        val initial=author.trim().take(1).uppercase()
        row.addView(ui.label(initial,14,true,ui.accent).apply {gravity=Gravity.CENTER; background=ui.bg(ui.pale,12)},
            LayoutParams(ui.d(34),ui.d(34)))
        val block=ui.column(); row.addView(block,ui.weighted().apply {leftMargin=ui.d(10)})
        block.addView(ui.label(author,15,true))
        block.addView(ui.label(ui.date(time)+"  ·  "+ui.str(badge),12,false,ui.muted),ui.lp(2))
        block.addView(ui.label(body,15).apply { setLineSpacing(ui.d(2).toFloat(),1f); setTextIsSelectable(true) },ui.lp(7))
        if(keep!=null) row.addView(ui.moderationMark(true,kept,if(kept)R.string.community_release_reply else R.string.community_keep_reply,keep),
            LayoutParams(ui.d(38),ui.d(44)))
        if(manage!=null) row.addView(ui.moderationMark(false,false,actionId,manage),LayoutParams(ui.d(38),ui.d(44)))
        rows.addView(row,ui.lp(12))
        rowViews.add(row)
    }
    private fun compose(post:CommunityPost) {
        val input=EditText(context).apply {
            setSingleLine(false); setMinLines(3); maxLines=6
            hint=ui.str(R.string.community_placeholder);filters=arrayOf(InputFilter.LengthFilter(1000)); gravity=Gravity.TOP
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val wrap=ui.column().apply {
            setPadding(ui.d(22),ui.d(10),ui.d(22),0); addView(input)
            addView(ui.label(ui.str(R.string.community_visible_here),12,false,ui.muted),ui.lp(5))
        }
        val dialog=AlertDialog.Builder(context).setTitle(R.string.community_write).setView(wrap)
            .setPositiveButton(R.string.community_send,null).setNegativeButton(R.string.community_cancel,null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(input.text.isBlank()){input.error=ui.str(R.string.community_empty_comment_msg); return@setOnClickListener}
            if(store.addComment(post,input.text.toString())!=null) {dialog.dismiss();refresh()}
            else input.error=ui.str(R.string.community_error_storage)
        } }
        dialog.show()
    }
}

class CommunityDetailActivity : Activity() {
    override fun attachBaseContext(base:Context)=super.attachBaseContext(AssemblyLanguage.wrap(base))
    private val ui by lazy { CommunityViews(this) }
    private val store by lazy { PortfolioActivityStore(this) }
    private lateinit var postId:String
    private lateinit var discussion:CommunityDiscussionView
    private lateinit var like:TextView
    private lateinit var save:TextView
    private var currentPost:CommunityPost?=null
    companion object {
        fun intent(context:Context,id:String)=Intent(context,CommunityDetailActivity::class.java).putExtra("post",id)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        postId=intent.getStringExtra("post").orEmpty()
        LightSystemBars.configure(window)
        val root=ui.column()
        LightSystemBars.insetContent(root,ui.d(20),ui.d(8),ui.d(20),0)
        root.setBackgroundColor(ui.paper)
        val bar=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        bar.addView(ui.icon("back",R.string.portfolio_back){finish()},LinearLayout.LayoutParams(ui.d(44),ui.d(44)))
        bar.addView(ui.label(ui.str(R.string.community_detail),16,true),ui.weighted().apply {leftMargin=ui.d(12)})
        root.addView(bar,ui.lp())
        val catalog=CommunityCatalog.get(this)
        val p=if(catalog.isHidden(postId)) null else catalog.post(postId) ?: store.post(postId)
        if(p==null) {
            root.addView(ui.label(ui.str(R.string.community_idea_missing),16),ui.lp(30)); setContentView(root); return
        }
        currentPost=p
        if(savedInstanceState==null) store.recordView(p)
        val body=ui.column().apply {setPadding(0,ui.d(14),0,ui.d(24))}
        val scroll=ScrollView(this).apply {id=android.R.id.list;isVerticalScrollBarEnabled=false;addView(body)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val art=ui.model(p,true).apply {background=ui.bg(ui.surface(p),24); clipToOutline=true; contentDescription=ui.str(R.string.community_model)}
        body.addView(art,LinearLayout.LayoutParams(-1,ui.d(236)))
        body.addView(ui.label(ui.title(p),29,true),ui.lp(17))
        val sourceLabel=ui.str(if(p.generated)R.string.community_ai else R.string.community_studio)
        body.addView(ui.label(listOf(sourceLabel,p.author,ui.date(p.timestamp)).joinToString("  ·  "),13,false,ui.muted),ui.lp(7))
        body.addView(ui.label(ui.body(p),16).apply {setLineSpacing(ui.d(3).toFloat(),1f)},ui.lp(12))
        val actions=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        like=ui.action("") {store.setLiked(p,!store.isLiked(p.id));reactions();discussion.refresh()}
        save=ui.action("") {store.setSaved(p,!store.isSaved(p.id));reactions()}
        actions.addView(like);actions.addView(save,LinearLayout.LayoutParams(-2,-2).apply {leftMargin=ui.d(8)})
        body.addView(actions,ui.lp(16))
        discussion=CommunityDiscussionView(this,postId)
        body.addView(discussion,ui.lp(6))
        reactions()
        setContentView(root)
    }
    private fun reactions() {
        val post=currentPost?:return
        val liked=store.isLiked(postId)
        val saved=store.isSaved(postId)
        like.text=ui.str(if(liked)R.string.community_liked_count else R.string.community_like_count,post.sampleLikes+store.localLikeCount(postId))
        save.text=ui.str(if(saved)R.string.community_saved_suffix else R.string.community_save)
        for ((button,checked,res) in listOf(Triple(like,liked,R.drawable.portfolio_heart),Triple(save,saved,R.drawable.portfolio_bookmark))) {
            button.isSelected=checked
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(res,0,0,0)
            button.compoundDrawablePadding=ui.d(5)
            button.setTextColor(if(checked) Color.WHITE else ui.ink)
            button.compoundDrawableTintList=android.content.res.ColorStateList.valueOf(if(checked) Color.WHITE else ui.ink)
            button.background=ui.bg(if(checked) ui.accent else ui.pale,16)
        }
    }
}

