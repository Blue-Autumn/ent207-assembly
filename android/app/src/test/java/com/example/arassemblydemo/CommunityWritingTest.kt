package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommunityWritingTest {
    @Test fun optInLiveFortyReplyContinuation(){
        val config=System.getenv("ASSEMBLY_TEST_COMMUNITY_CONFIG")
        org.junit.Assume.assumeTrue("Live provider test is opt-in",!config.isNullOrBlank())
        val credentials=JSONObject(java.io.File(config!!).readText().removePrefix("\uFEFF"))
        require(credentials.getString("base_url").trimEnd('/')=="https://api.deepseek.com")
        val now=System.currentTimeMillis()
        val p=requireNotNull(CommunitySeeds.find("recordplayer",now))
        val first=JSONArray(java.io.File("src/main/assets/community/replies/recordplayer.json").readText())
        val history=List(first.length()){i->val row=first.getJSONArray(i);CommunitySample("Reader",row.getString(0),row.getString(1),now)}
        val queries=CommunityWriting.replyRequests(p.copy(samples=history),40,4,"balanced","",emptyList())
        val pool=java.util.concurrent.Executors.newFixedThreadPool(queries.size)
        val done=java.util.concurrent.ExecutorCompletionService<List<CommunitySample>>(pool)
        val earlier=history.mapTo(mutableSetOf()) {it.textEn.lowercase()}
        try {
            queries.forEach {request->done.submit(java.util.concurrent.Callable {
                CommunityWriting.comments(CommunityWriting.complete(request,credentials.getString("api_key")),p.timestamp,now)
            })}
            var added=0
            repeat(queries.size){
                val fresh=done.take().get().count {earlier.add(it.textEn.lowercase())}
                added+=fresh
                println("Live turntable: +$fresh, $added total, ${System.currentTimeMillis()-now}ms")
            }
            assertTrue(added>=36)
        } finally {pool.shutdownNow()}
    }
    private fun work(i:Int)=JSONObject().put("model",CommunityModel.example(i).toJson())
        .put("titleEn","Shelf $i").put("titleZh","搁板 $i").put("bodyEn","A green rail.").put("bodyZh","绿色横撑。")
        .put("comments",JSONArray().put(JSONObject().put("textEn","Room for keys?").put("textZh","放得下钥匙吗？")))

    @Test fun paidResponseRequiresCompleteBatchAndARealShape(){
        val valid=work(1)
        val content=JSONObject().put("works",JSONArray().put(valid))
        val post=CommunityWriting.posts(content,1,3,2_000_000_000_000L).single()
        assertEquals(post,CommunityPost.fromJson(post.toJson()))
        assertTrue(post.samples.single().timestamp in post.timestamp..2_000_000_000_000L)
        assertTrue(runCatching { CommunityWriting.posts(content,2,3,2_000_000_000_000L) }.isFailure)
        valid.put("model",JSONObject().put("parts",JSONArray().put(JSONObject().put("code","eval"))))
        assertTrue(runCatching { CommunityWriting.posts(content,1,3,2_000_000_000_000L) }.isFailure)
    }
    @Test fun referencePrefixIsIdenticalAcrossBatchesAndNewPosts(){
        val a=CommunityWriting.request(1,4,"plain","One stool",listOf("nice"),null,"1")
        val b=CommunityWriting.request(2,1,"playful","Two tables",emptyList(),null,"2")
        fun prefix(o:JSONObject)=o.getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals(prefix(a),prefix(b))
        assertFalse(prefix(a).contains("One stool"))
        assertFalse(a.toString().contains("image_url"))
    }
    @Test fun offlineCatalogCanOpenBookmarksByCanonicalId(){
        val anchor=2_000_000_000_000L
        for(id in CommunitySeeds.catalog.map {it.key} + listOf("study-0","study-49")){
            val p=requireNotNull(CommunitySeeds.find(id,anchor))
            assertEquals(id,p.id)
            assertTrue(p.samples.isNotEmpty())
            assertEquals(p,CommunityPost.fromJson(p.toJson()))
            assertTrue(p.templateKey!=null || p.model!=null)
        }
        assertNull(CommunitySeeds.find("studio-removed",anchor))
        assertNull(CommunitySeeds.find("study--10",anchor))
        assertNull(CommunitySeeds.find("0",anchor))
    }
}
