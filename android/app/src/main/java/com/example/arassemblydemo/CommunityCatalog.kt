package com.example.arassemblydemo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor

/** Shared, device-local demo content. Account reactions and user-written comments live elsewhere. */
internal class CommunityCatalog internal constructor(private val context:Context,
    private val complete:(JSONObject)->JSONObject={ CommunityWriting.complete(it,AiProviderSettings(context).load()) }) {
    private val prefs=context.getSharedPreferences("community-studio-v1",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private val workers=Executors.newFixedThreadPool(3) as ThreadPoolExecutor
    private val replies=Executors.newFixedThreadPool(8) as ThreadPoolExecutor
    private class ReplyBatch {
        val futures=mutableListOf<Future<*>>()
        var remaining=4
        var readThrough=0
        var added=0
        var failed=false
    }
    private val replyJobs=mutableMapOf<String,ReplyBatch>()
    private val retryAfter=mutableMapOf<String,Long>()
    private val installedReplyBatches=prefs.getStringSet("installedReplyBatches",emptySet()).orEmpty().toMutableSet()
    private val bundledReplyIds by lazy { context.assets.list("community/replies").orEmpty().mapTo(hashSetOf()){it.removeSuffix(".json")} }
    private val keptSamples=prefs.getStringSet("keptSamples",emptySet()).orEmpty().toMutableSet()
    private var completed=0
    private var requested=0
    private var failed=0
    private val listeners=CopyOnWriteArraySet<()->Unit>()
    private val anchor=prefs.getLong("anchor",0L).takeIf { it>0 } ?: System.currentTimeMillis().also {
        prefs.edit().putLong("anchor",it).apply()
    }
    private val deskStudies=OfflineDeskStudies(anchor)
    private val online=linkedMapOf<String,CommunityPost>()
    // A post already on screen survives disk-cache rotation until the viewer refreshes.
    private val sessionArchive=mutableMapOf<String,CommunityPost>()
    private val extras=mutableMapOf<String,List<CommunitySample>>()
    private val blocked=linkedMapOf<String,CommunityModeration>()
    private val archived=mutableMapOf<String,CommunityPost>()
    private val kept=prefs.getStringSet("kept",emptySet()).orEmpty().toMutableSet()
    private val feed=mutableListOf<String>()
    private val displayedOnline=mutableSetOf<String>()
    private var seedIndex=0
    private var edition=0
    private var serial=0L
    private var busy=false
    private var nextAuto=0L
    private var jobs=mutableListOf<Future<*>>()
    private var error:String?=null
    private var revision=prefs.getLong("revision",0)
    private var prompt=prefs.getString("prompt","").orEmpty()
    private var months=prefs.getInt("months",4).coerceIn(1,24)
    private var auto=prefs.getBoolean("auto",true)
    private var debug=prefs.getBoolean("debug",false)
    private var style=prefs.getString("style","balanced").orEmpty()
    private var batch=prefs.getInt("batch",24).coerceIn(1,24)
    private val changedReplyIds=mutableSetOf<String>()
    init {
        fun rows(k:String)=runCatching { JSONArray(prefs.getString(k,"[]")) }.getOrElse { JSONArray() }
        fun objects(k:String,read:(JSONObject)->Unit) {
            val arr=rows(k)
            for(i in 0 until arr.length()) runCatching { read(arr.getJSONObject(i)) }
        }
        objects("online"){ CommunityPost.fromJson(it).also { p->online[p.id]=p } }
        val oldUntouched=online.keys.any { (it in OLD_STARTER || it.startsWith("launch2-")) && (!pinned(it) || revision==0L) }
        if(prefs.getInt("launchVersion",0)<3 && (!prefs.contains("online") || oldUntouched)){
            val starter=context.assets.open("community/launch.json").bufferedReader().use { JSONArray(it.readText()) }
            for(i in 0 until starter.length()){
                val p=CommunityPost.fromJson(starter.getJSONObject(i))
                val old=online.remove(p.id)
                online[p.id]=when {
                    old==null -> p
                    p.id.startsWith("launch2-") && !pinned(p.id) -> p.copy(samples=old.samples)
                    else -> old
                }
            }
            prefs.edit().putString("online",JSONArray(online.values.map { it.toJson() }).toString()).putInt("launchVersion",3).apply()
        } else if(prefs.getInt("launchVersion",0)<3){
            // A legacy store with no surviving unpinned starter may have been deliberately cleared.
            prefs.edit().putInt("launchVersion",3).apply()
        }
        objects("archived"){ CommunityPost.fromJson(it).also { p->archived[p.id]=p } }
        objects("blocked"){ CommunityModeration.fromJson(it).also { m->blocked[m.key]=m } }
        objects("extras"){ row->
            val samples=row.getJSONArray("items")
            extras[row.getString("id")]=List(samples.length()){CommunitySample.fromJson(samples.getJSONObject(it))}
        }
        for((key,value) in prefs.all){
            if(!key.startsWith("replyItems/") || value !is String)continue
            val id=key.substringAfter('/')
            val rows=JSONArray(value)
            extras[id]=List(rows.length()){CommunitySample.fromJson(rows.getJSONObject(it))}
            changedReplyIds.add(id)
        }
    }

    fun addListener(callback:()->Unit){listeners.add(callback)}
    fun removeListener(callback:()->Unit){listeners.remove(callback)}
    private fun notifyChanged(){main.post { listeners.forEach { it() } }}
    @Synchronized fun status()=CommunityStatus(busy,online.keys.count { it !in OLD_STARTER && it !in blocked },prompt,months,auto,error,revision,debug,style,batch,
        completed,requested,failed)
    @Synchronized fun moderation()=blocked.values.toList().asReversed()
    @Synchronized fun isHidden(id:String)=id in blocked
    @Synchronized fun isKept(postId:String)=postId in kept
    @Synchronized fun commentBusy(postId:String)=postId in replyJobs
    @Synchronized fun keptReplyKeys(postId:String)=keptSamples.filter {it.startsWith("$postId/")}.mapTo(hashSetOf()) {it.substringAfter('/')}
    @Synchronized fun isSampleKept(postId:String,index:Int):Boolean {
        val c=post(postId)?.samples?.getOrNull(index) ?: return false
        return "$postId/${c.key}" in keptSamples
    }
    @Synchronized fun keepSample(postId:String,index:Int,keep:Boolean){
        val c=post(postId)?.samples?.getOrNull(index) ?: return
        val key="$postId/${c.key}"
        if(keep){promote(postId);keptSamples.add(key)} else keptSamples.remove(key)
        store();notifyChanged()
    }
    @Synchronized fun retained():List<CommunityModeration> {
        val rows=mutableListOf<CommunityModeration>()
        for(id in kept)source(id)?.let { rows+=CommunityModeration(id,id,false,it.titleEn,it.titleZh,"") }
        for(key in keptSamples){
            val id=key.substringBefore('/')
            raw(id)?.samples?.firstOrNull { "$id/${it.key}"==key }?.let {
                rows+=CommunityModeration(key,id,true,it.textEn,it.textZh,"")
            }
        }
        return rows
    }
    @Synchronized fun release(item:CommunityModeration){
        if(item.sample)keptSamples.remove(item.key) else kept.remove(item.postId)
        store();notifyChanged()
    }
    private fun saveReplies(id:String, imported:Boolean=false){
        val items=extras[id].orEmpty()
        val edit=prefs.edit().putString("replyItems/$id",JSONArray(items.map {it.toJson()}).toString())
        if(imported)edit.putStringSet("installedReplyBatches",installedReplyBatches.toSet())
        changedReplyIds.add(id)
        edit.apply()
    }
    private fun store(){
        fun jsonRows(list:Collection<JSONObject>)=JSONArray(list).toString()
        prefs.edit().putString("online",jsonRows(online.values.map { it.toJson() }))
            .putString("archived",jsonRows(archived.values.map { it.toJson() }))
            .putString("blocked",jsonRows(blocked.values.map { it.toJson() }))
            .putString("extras",jsonRows(extras.map { (id,rows)->JSONObject().put("id",id).put("items",JSONArray(rows.map { it.toJson() })) }))
            .putStringSet("kept",kept.toSet()).putStringSet("keptSamples",keptSamples.toSet())
            .putLong("revision",revision).also { edit->
                changedReplyIds.forEach {edit.remove("replyItems/$it")}
                changedReplyIds.clear()
            }.apply()
    }
    @Synchronized fun configure(prompt:String,months:Int,auto:Boolean,style:String=this.style,batch:Int=this.batch){
        this.prompt=prompt.trim().take(480);this.months=months.coerceIn(1,24);this.auto=auto
        this.style=style.takeIf { it in listOf("plain","balanced","playful") } ?: "balanced"
        this.batch=batch.coerceIn(1,24)
        prefs.edit().putString("prompt",this.prompt).putInt("months",this.months).putBoolean("auto",auto)
            .putString("style",this.style).putInt("batch",this.batch).apply()
        notifyChanged()
    }
    @Synchronized fun setDebug(enabled:Boolean){debug=enabled;prefs.edit().putBoolean("debug",debug).apply();notifyChanged()}
    @Synchronized fun keep(postId:String,keep:Boolean){
        if(source(postId)==null || isHidden(postId))return
        if(keep){promote(postId);kept.add(postId)} else kept.remove(postId)
        store();notifyChanged()
    }
    private fun source(id:String)=online[id] ?: sessionArchive[id] ?: if(id.startsWith("local-desk-"))
        deskStudies.get(id) else CommunitySeeds.find(id,anchor)
    private fun promote(id:String){
        sessionArchive.remove(id)?.let { online.putIfAbsent(id,it) }
    }
    private fun raw(id:String):CommunityPost? {
        val p=source(id) ?: return null
        val extra=extras[id].orEmpty()
        return if(extra.isEmpty())p else p.copy(samples=(p.samples+extra).distinctBy { it.key })
    }
    /** Only opening a discussion reads its own small batch. A saved older design can still receive new replies. */
    @Synchronized fun openDiscussion(postId:String, saved:()->CommunityPost?) {
        if(postId in blocked)return
        val post=source(postId) ?: saved()?.also { sessionArchive[postId]=it } ?: return
        if(postId !in bundledReplyIds || postId in installedReplyBatches)return
        val rows=context.assets.open("community/replies/$postId.json").bufferedReader().use { JSONArray(it.readText()) }
        val people=CommunitySeeds.people
        val offset=Math.floorMod(postId.hashCode(),people.size)
        val end=maxOf(post.timestamp,minOf(System.currentTimeMillis(),post.timestamp+3*CommunitySeeds.day))
        val samples=List(rows.length()) { i->
            val row=rows.getJSONArray(i)
            CommunitySample(people[(i+offset)%people.size],row.getString(0),row.getString(1),
                post.timestamp+(end-post.timestamp)/(rows.length()+1)*(i+1))
        }
        val existing=raw(postId)!!.samples.mapTo(hashSetOf()){it.textEn.lowercase()}
        extras[postId]=extras[postId].orEmpty()+samples.filter { existing.add(it.textEn.lowercase()) }
        installedReplyBatches.add(postId)
        // Save this import and its receipt together so a restart never reintroduces moderated replies.
        saveReplies(postId,imported=true)
    }
    @Synchronized fun post(id:String):CommunityPost? {
        if(id in blocked) return null
        val p=raw(id) ?: return null
        if(blocked.isEmpty())return p
        return p.copy(samples=p.samples.filter { "$id/${it.key}" !in blocked })
    }

    @Synchronized fun refresh(){
        if(feed.isNotEmpty())edition=(edition+1)%10000
        feed.clear();displayedOnline.clear();sessionArchive.clear();seedIndex=0
    }
    @Synchronized fun page(offset:Int,limit:Int=8):List<CommunityPost> {
        if(offset<0 || offset>feed.size+256 || limit<=0)return emptyList()
        val end=offset+limit.coerceAtMost(40)
        while(feed.size<end){
            // Existing screen positions stay fixed while a new batch downloads.
            val fresh=if(feed.size%3!=0) online.keys.lastOrNull { recommendable(it) } else null
            if(fresh!=null){feed.add(fresh);displayedOnline.add(fresh)}
            else{
                val i=seedIndex++
                val originals=CommunitySeeds.catalog
                val id=if(i<originals.size) originals[(i+edition*3)%originals.size].key else {
                    val next=i-originals.size
                    if(next%8==7)"study-"+(next/8+edition*2)
                    else {
                        val index=next-next/8+edition*5
                        if("launch2-"+deskStudies.family(index) in blocked)continue
                        "local-desk-$index"
                    }
                }
                if(id !in blocked)feed.add(id)
            }
        }
        return feed.subList(offset,end).mapNotNull { post(it) }
    }

    @Synchronized fun hidePost(id:String,reason:String=""){
        val p=raw(id)?:return
        if(id in blocked)return
        kept.remove(id)
        keptSamples.removeAll { it.startsWith("$id/") }
        blocked[id]=CommunityModeration(id,id,false,p.titleEn,p.titleZh,reason.trim().take(180))
        archived[id]=p
        revision++;refresh();store();notifyChanged()
    }
    @Synchronized fun hideSample(postId:String,index:Int,reason:String=""){
        val comment=post(postId)?.samples?.getOrNull(index)?:return
        val key="$postId/${comment.key}"
        keptSamples.remove(key)
        blocked[key]=CommunityModeration(key,postId,true,comment.textEn,comment.textZh,reason.trim().take(180))
        raw(postId)?.let { archived[postId]=it }
        revision++;store();notifyChanged()
    }
    @Synchronized fun restore(item:CommunityModeration){
        val restored=blocked.remove(item.key) ?: return
        val backup=archived[restored.postId]
        if(backup!=null){
            val current=raw(backup.id)
            if(current==null && backup.generated)online[backup.id]=backup
            else if(current!=null && restored.sample){
                val c=backup.samples.firstOrNull { "${backup.id}/${it.key}"==restored.key }
                if(c!=null && current.samples.none { it.key==c.key })extras[backup.id]=extras[backup.id].orEmpty()+c
            }
            if(blocked.values.none { it.postId==backup.id })archived.remove(backup.id)
        }
        revision++;refresh();store();notifyChanged()
    }

    private fun invalidate(){
        serial++;jobs.forEach { it.cancel(true) };jobs.clear();workers.purge();busy=false;error=null
    }
    private fun parentOfPinnedSample(id:String)=keptSamples.any { it.startsWith("$id/") }
    private fun pinned(id:String)=id in kept || parentOfPinnedSample(id)
    private fun dropUnkept() {
        val removed=online.keys.filterNot(::pinned)
        for(id in removed)online.remove(id)
        for((id,p) in online.toMap()){
            online[id]=p.copy(samples=p.samples.filter { "$id/${it.key}" in keptSamples })
        }
        extras.replaceAll { id,rows->rows.filter { "$id/${it.key}" in keptSamples } }
        extras.entries.removeAll { it.value.isEmpty() }
        retryAfter.clear()
        replyJobs.values.flatMap { it.futures }.forEach { it.cancel(true) };replyJobs.clear();replies.purge()
        refresh();revision++
    }
    @Synchronized fun clearGenerated(){
        invalidate();dropUnkept()
        completed=0;requested=0;failed=0
        nextAuto=SystemClock.elapsedRealtime()+60_000
        store();notifyChanged()
    }
    @Synchronized fun requestMore(){
        if(!auto || busy || SystemClock.elapsedRealtime()<nextAuto)return
        if(online.keys.count(::recommendable)>=16)return
        generate()
    }
    private fun recommendable(id:String)=id !in displayedOnline && id !in blocked && id !in OLD_STARTER
    @Synchronized fun generate(count:Int=batch){ startBatch(count.coerceIn(1,24),false) }
    @Synchronized fun regenerate(){
        invalidate();startBatch(batch,true)
    }
    @Synchronized fun ensureComments(postId:String,readThrough:Int=0) {
        if(!auto || (retryAfter[postId]?:0)>SystemClock.elapsedRealtime())return
        replyJobs[postId]?.let { it.readThrough=maxOf(it.readThrough,readThrough);return }
        val count=post(postId)?.samples?.size ?: return
        val threshold=40+((readThrough-10).coerceAtLeast(0)/40)*40
        if(count>=threshold)return
        refreshComments(postId)
    }

    @Synchronized fun refreshComments(postId:String){
        if(postId in replyJobs)return
        val post=post(postId)?:return
        val count=if(post.samples.size<20)20 else 40
        val queries=CommunityWriting.replyRequests(post,count,months,style,prompt,negativeExamples())
        val batch=ReplyBatch()
        batch.remaining=queries.size
        replyJobs[postId]=batch
        for(query in queries) {
            batch.futures+=replies.submit {
                val result=runCatching { CommunityWriting.comments(complete(query),post.timestamp,System.currentTimeMillis()) }
                synchronized(this){
                    if(replyJobs[postId]!==batch)return@submit
                    val current=raw(postId)
                    if(current!=null && !isHidden(postId)){
                        val seen=current.samples.mapTo(hashSetOf()){it.textEn.trim().lowercase()}
                        val fresh=result.getOrNull().orEmpty().filter {seen.add(it.textEn.trim().lowercase())}
                        if(fresh.isNotEmpty()){
                            extras[postId]=extras[postId].orEmpty()+fresh
                            saveReplies(postId)
                            batch.added+=fresh.size
                        }
                    }
                    if(result.isFailure)batch.failed=true
                    if(--batch.remaining==0){
                        replyJobs.remove(postId)
                        if(batch.failed || batch.added==0)retryAfter[postId]=SystemClock.elapsedRealtime()+5000
                        else retryAfter.remove(postId)
                        if(batch.readThrough>=10 && batch.added>0)ensureComments(postId,batch.readThrough)
                    }
                    notifyChanged()
                }
            }
        }
        notifyChanged()
    }
    private fun negativeExamples()=listOfNotNull(
        blocked.values.lastOrNull { !it.sample && archived[it.postId]?.generated==true },
        blocked.values.lastOrNull { it.sample }).map { it.titleEn }
    private fun errorCode(e:Exception)=when(e){
        is AiReviewException -> if(e.code in listOf("AUTH_FAILED","NOT_CONFIGURED"))"key" else "busy"
        is IOException -> "offline"
        else -> "format"
    }
    private fun trimCache(){
        val eligible=online.keys.filterNot(::pinned)
        for(id in eligible.take((eligible.size-CACHE_LIMIT).coerceAtLeast(0))){
            if(id in displayedOnline)raw(id)?.let { sessionArchive[id]=it }
            online.remove(id);extras.remove(id)
        }
    }
    private fun startBatch(n:Int,replace:Boolean){
        if(busy)return
        val token=++serial
        requested=n;completed=0;failed=0;busy=true;error=null
        nextAuto=SystemClock.elapsedRealtime()+20_000
        val selected=CommunityDesignBrief.keys.shuffled().take(n)
        val timeMonths=months
        val notes=negativeExamples()
        val titles=mutableSetOf<String>()
        var replaced=false
        for(keys in selected.chunked(2)){
            val q=CommunityWriting.request(keys.size,timeMonths,style,prompt,notes,null,UUID.randomUUID().toString(),keys)
            jobs.add(workers.submit {
                try {
                    val data=complete(q)
                    val incoming=CommunityWriting.validChunk(data,keys,timeMonths,System.currentTimeMillis())
                    require(incoming.isNotEmpty())
                    synchronized(this){
                        if(serial!=token)return@submit
                        if(replace && !replaced){dropUnkept();replaced=true}
                        if(incoming.size<keys.size){failed+=keys.size-incoming.size;error="format"}
                        for(post in incoming){
                            if(titles.add(post.titleEn.lowercase())){online[post.id]=post;completed++}
                            else {failed++;error="format"}
                        }
                        trimCache();store()
                    }
                }catch(e:Exception){
                    synchronized(this){if(serial==token){failed+=keys.size;error=errorCode(e)}}
                }finally{
                    synchronized(this){
                        if(serial==token){
                            if(completed+failed==requested){
                                busy=false;jobs.clear()
                                if(failed>0)nextAuto=SystemClock.elapsedRealtime()+60_000
                            }
                            notifyChanged()
                        }
                    }
                }
            })
        }
        notifyChanged()
    }
    companion object {
        private val OLD_STARTER=setOf("studio-0a778e21-fccc-46e8-b41e-bedd2b097eff","studio-b8598e95-11a1-44d3-91da-fbe349302ebd")
        private const val CACHE_LIMIT=96
        @Volatile private var singleton:CommunityCatalog?=null
        fun get(context:Context):CommunityCatalog=singleton ?: synchronized(this){
            singleton ?: CommunityCatalog(context.applicationContext).also {singleton=it}
        }
    }
}
