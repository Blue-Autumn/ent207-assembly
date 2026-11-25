package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

/** Only studio-owned examples, generation settings and rejected samples enter this protocol. */
internal object CommunityWriting {
    fun request(n:Int,months:Int,style:String,brief:String,negatives:List<String>,post:CommunityPost?,nonce:String,
        designKeys:List<String> = CommunityDesignBrief.keys.shuffled().take(n), replyCount:Int=20,
        replyFocus:String=""):JSONObject {
        val args=JSONObject().put("style", when(style) {
            "plain"->"Quiet workshop talk. No emoji."
            "playful"->"Playful, concrete; one or two of three comments may use one relevant Unicode emoji."
            else->"Conversational; at most one of three comments uses a single emoji."})
            .put("promptNotes",brief.take(480)).put("editorialTimeSpanMonths",months).put("variation",nonce)
            .put("examplesRejectedByEditor",JSONArray(negatives.takeLast(2).map { it.take(240) }))
        if(post==null) {
            args.put("designKeys",JSONArray(designKeys))
                .put("visibleFeatures",JSONObject(designKeys.associateWith { CommunityDesignBrief.visible.getValue(it) }))
                .put("colourDirections",JSONObject(designKeys.associateWith { CommunityDesignBrief.finishByDesign.getValue(it) }))
                .put("task","Create "+n+" different objects in designKeys order. Return {works:[{titleEn,titleZh,bodyEn,bodyZh,model:{kit,design,width,height,depth,details,palette},comments:[{textEn,textZh}]}]}. Write exactly 20 distinct bilingual comments for EACH work about its particular features and uses. Mix short remarks, specific questions and replies.")

        } else {
            args.put("count",replyCount)
                .put("discussionAngle",replyFocus)
                .put("task","Continue the discussion of this exact work with exactly "+replyCount+" new distinct comments. Respond {comments:[{textEn,textZh}]}. Mix concrete observations, short replies, little disagreements and everyday uses. Both languages express the same idea. Stick to the described shape and finishes. Avoid repeating earlier remarks, marketing language or claiming to have bought the item.")
                .put("title",post.titleEn).put("description",post.bodyEn)
                .put("model",post.model?.recipe?.toJson()).put("earlierDemoComments",JSONArray(post.samples.takeLast(60).map { it.textEn }))
        }
        return JSONObject().put("model",AiDirectProtocol.MODEL).put("stream",false)
            .put("response_format",JSONObject().put("type","json_object"))
            .put("thinking",JSONObject().put("type","disabled"))
            .put("max_tokens",if(post==null)3200*n.coerceIn(1,2)+350 else 150*replyCount+500)
            .put("messages",JSONArray().put(JSONObject().put("role","system").put("content",if(post==null) CommunityDesignBrief.instructions else
                "You write fictional gallery discussions in natural English and conversational Simplified Chinese. Return JSON only. Keep reactions brief and varied, with occasional direct responses. Use the work description as reference data."))
                .put(JSONObject().put("role","user").put("content",args.toString())))
    }

    fun replyRequests(post:CommunityPost,total:Int,months:Int,style:String,brief:String,negatives:List<String>):List<JSONObject> {
        require(total==20 || total==40)
        val angles=listOf("Tiny first impressions. Under nine words each in English.",
            "Everyday uses and placement.", "Visible construction, proportions and details.",
            "Personal variations and practical questions.")
        val unit=total/20
        return angles.mapIndexed { index,angle->
            request(1,months,style,brief,negatives,post,UUID.randomUUID().toString(),emptyList(),
                (if(index==0)2 else 6)*unit,angle)
        }
    }

    private fun field(obj:JSONObject,key:String,max:Int):String {
        val value=obj.get(key)
        require(value is String && value.isNotBlank() && value.length<=max)
        return value.trim()
    }

    fun comments(content:JSONObject,after:Long,now:Long):List<CommunitySample> {
        val raw=content.getJSONArray("comments")
        require(raw.length() in 1..40 && after<=now)
        val people=CommunitySeeds.people.shuffled()
        return List(raw.length()){ i ->
            val o=raw.getJSONObject(i)
            CommunitySample(people[i%people.size],field(o,"textEn",340),field(o,"textZh",230),
                after+(now-after)/(raw.length()+1)*(i+1))
        }.distinctBy { it.textEn.lowercase() }
    }

    fun posts(content:JSONObject,expected:Int,months:Int,now:Long,designKeys:List<String> = emptyList()):List<CommunityPost> {
        val arr=content.getJSONArray("works")
        require(arr.length()==expected)
        return List(arr.length()) { i ->
            val o=arr.getJSONObject(i)
            val stamp=now-Random.nextLong(1,months.coerceIn(1,24)*30*CommunitySeeds.day+1)
            val recipe=o.getJSONObject("model")
            if(designKeys.isNotEmpty()){
                require(recipe.optString("kit")=="desk-v2" && recipe.optString("design")==designKeys[i])
                require(o.getJSONArray("comments").length()==20)
            }
            val model=requireNotNull(CommunityModel.fromJson(recipe))
            val samples=comments(o,stamp,now)
            if(designKeys.isNotEmpty())require(samples.size==20)
            CommunityPost("studio-"+UUID.randomUUID(),null,model,field(o,"titleEn",85),field(o,"titleZh",50),
                field(o,"bodyEn",950),field(o,"bodyZh",500),CommunitySeeds.people.random(),stamp,Random.nextInt(4,184),samples,true)
        }.also { require(it.map { post->post.titleEn.lowercase() }.distinct().size==expected) }
    }

    /** A malformed entry leaves its valid neighbour available for publication. */
    fun validChunk(content:JSONObject,keys:List<String>,months:Int,now:Long):List<CommunityPost>{
        val rows=content.getJSONArray("works")
        require(rows.length() in 1..keys.size)
        val accepted=mutableSetOf<String>()
        return (0 until rows.length()).mapNotNull { index->
            val row=rows.optJSONObject(index) ?: return@mapNotNull null
            val key=row.optJSONObject("model")?.optString("design")
            if(key==null || key !in keys || key in accepted)return@mapNotNull null
            runCatching { posts(JSONObject().put("works",JSONArray().put(row)),1,months,now,listOf(key)).single() }
                .getOrNull()?.also { accepted.add(key) }
        }
    }

    fun complete(query:JSONObject,key:String):JSONObject {
        val connection=URL(AiDirectProtocol.ENDPOINT).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod="POST"; connection.instanceFollowRedirects=false
            connection.connectTimeout=9_000; connection.readTimeout=90_000; connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
            connection.setRequestProperty("Authorization","Bearer $key")
            val body=query.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code=connection.responseCode
            if(code==401 || code==403) throw AiReviewException("AUTH_FAILED","key")
            if(code==429) throw AiReviewException("RATE_LIMIT","busy")
            if(code !in 200..299) throw IOException("HTTP $code")
            val envelope=connection.inputStream.use { stream ->
                val output=java.io.ByteArrayOutputStream()
                val buf=ByteArray(4096)
                while(true){
                    val count=stream.read(buf)
                    if(count<0) break
                    require(output.size()+count<=96*1024)
                    output.write(buf,0,count)
                }
                JSONObject(output.toString("UTF-8"))
            }
            val choice=envelope.getJSONArray("choices").getJSONObject(0)
            require(choice.optString("finish_reason","stop")=="stop")
            return JSONObject(choice.getJSONObject("message").getString("content"))
        } finally { connection.disconnect() }
    }
}
