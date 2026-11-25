package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class CommunitySample(val author:String, val textEn:String, val textZh:String, val timestamp:Long) {
    val key:String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val bytes=MessageDigest.getInstance("SHA-256").digest("$author\n$textEn\n$textZh".toByteArray())
        val hex="0123456789abcdef"
        buildString(20) { for(i in 0 until 10){ val b=bytes[i].toInt() and 255; append(hex[b ushr 4]); append(hex[b and 15]) } }
    }
    fun toJson() = JSONObject().put("author",author).put("en",textEn).put("zh",textZh).put("at",timestamp)
    companion object {
        fun fromJson(o:JSONObject) = CommunitySample(o.getString("author"),o.getString("en"),o.getString("zh"),o.getLong("at"))
    }
}

internal data class CommunityPost(val id:String, val templateKey:String?, val model:CommunityModel?,
    val titleEn:String, val titleZh:String, val bodyEn:String, val bodyZh:String, val author:String,
    val timestamp:Long, val sampleLikes:Int, val samples:List<CommunitySample>, val generated:Boolean) {
    fun toJson():JSONObject = JSONObject().put("id",id).put("template",templateKey).put("model",model?.toJson())
        .put("titleEn",titleEn).put("titleZh",titleZh).put("bodyEn",bodyEn).put("bodyZh",bodyZh)
        .put("author",author).put("at",timestamp).put("likes",sampleLikes)
        .put("samples",JSONArray(samples.map { it.toJson() })).put("generated",generated)
    companion object {
        fun fromJson(o:JSONObject):CommunityPost {
            val arr=o.getJSONArray("samples")
            return CommunityPost(o.getString("id"),o.optString("template").ifEmpty { null },o.optJSONObject("model")?.let(CommunityModel::fromJson),
                o.getString("titleEn"),o.getString("titleZh"),o.getString("bodyEn"),o.getString("bodyZh"),o.getString("author"),
                o.getLong("at"),o.getInt("likes"),List(arr.length()){ CommunitySample.fromJson(arr.getJSONObject(it)) },o.getBoolean("generated"))
        }
    }
}

internal data class CommunityModeration(val key:String,val postId:String,val sample:Boolean,val titleEn:String,val titleZh:String,val reason:String) {
    fun toJson() = JSONObject().put("key",key).put("id",postId).put("sample",sample).put("en",titleEn).put("zh",titleZh).put("reason",reason)
    companion object {
        fun fromJson(o:JSONObject)=CommunityModeration(o.getString("key"),o.getString("id"),o.getBoolean("sample"),
            o.getString("en"),o.getString("zh"),o.optString("reason"))
    }
}

internal data class CommunityStatus(val busy:Boolean,val generatedCount:Int,val prompt:String,val months:Int,val auto:Boolean,
    val lastError:String?,val revision:Long,val debug:Boolean,val style:String,val batch:Int,
    val completed:Int=0,val requested:Int=0,val failed:Int=0)

internal data class StudioSeed(val key:String,val titleEn:String,val titleZh:String,val en:String,val zh:String,
    val commentEn:String,val commentZh:String,val replyEn:String,val replyZh:String)

internal object CommunitySeeds {
    val catalog=listOf(
        StudioSeed("stool","Crossbar stool","四方矮凳","Four rails meet under a square seat. I kept the sides open so a pair of shoes can slide underneath.","四根横撑围住凳脚，中间留空，换鞋时刚好可以把拖鞋推到座面下。","I'd put this by the door. Plenty of room under it for my clogs.","想放在玄关，下面正好藏我那双木屐。","Seeing the four rails slide in makes that joint click for me.","四根横撑一收拢，我突然看懂它怎么卡住了。"),
        StudioSeed("lamp","Amber desk lamp","琥珀台灯","The shade sits on a cross brace, leaving a ribbon of warm light at its lower edge. The small base leaves room for an open notebook.","灯罩落在十字撑上，暖光沿下沿散下来。底座只占桌角一小块，旁边还能摊开笔记本。","That little strip of amber under the shade is my favourite bit.","我最喜欢灯罩边上漏出来的那一线橘光。","Would a taller stem clear a monitor?","灯杆再高一点能越过显示器吗？"),
        StudioSeed("recordplayer","Walnut turntable","胡桃木唱机","A thick wooden plinth anchors the spinning record. The little brass plate sits low on the front, away from the tonearm.","厚木底座托着转盘，黄铜铭牌藏在前侧偏下的位置，把目光留给唱针。","The brass plate reminds me of my dad's old amp.","这块小铭牌让我想到我爸以前那台功放。","I keep watching that record go round.","我居然一直盯着唱片转。"),
        StudioSeed("bridge","Garden footbridge","花园小桥","The deck spans between two low banks. Viewed from the side, the rail makes the short crossing feel like a longer walk.","桥面搭在两端的矮岸之间。转到侧面看，矮栏杆把短短几步衬得像一段小路。","Would be a fun piece in a little railway layout.","挺适合塞进模型铁路的场景里。","I'd try a darker colour just on the rail.","我想只把扶手换成深一点的颜色。"),
        StudioSeed("bookstand","Reading ledge","桌边书架","The books settle one at a time along the ledge. The open end leaves a spot for whichever paperback comes home next.","几本书依次落在搁板上，最外边留了点空位，给下一本带回家的口袋书。","The last little book dropping in is very satisfying.","最后那本小书落下去，看着特别舒服。","I'd want this right next to my tea mug.","想放在我茶杯旁边，伸手就能抽本书。"),
        StudioSeed("tray","Catch-all tray","桌面小托盘","A low lip keeps a pencil from rolling across the desk. The notebook and small objects fit without piling on top of each other.","浅浅的一圈挡边让铅笔停在桌角，薄本子和零碎小物也能各占一处。","Finally a home for the pencil that lives under my keyboard.","我键盘底下那支笔终于有家了。","The shallow rim looks easy to reach over.","边这么低，拿东西应该挺顺手。"),
        StudioSeed("xylophone","Pocket xylophone","桌上木琴","The bars step down in size across a pair of supports. Their different lengths make a rhythm you can see before playing a note.","音条顺着两根支撑逐渐变短，哪怕还没有敲响，也能看出一点节奏。","My nephew would head straight for the shortest bar.","我外甥肯定先敲最短的那块。","I like the gap between each bar.","每条之间的间距看着真舒服。"),
        StudioSeed("drawers","Two-drawer cabinet","双色抽屉柜","The deep-coloured lower drawer opens wider than the upper one. That bit of asymmetry helps the little cabinet feel grounded.","颜色更深的下抽屉拉出来更多，上抽屉只露出一截，放在书桌边看起来挺稳。","My cable drawer needs to be the deeper one too.","我那个装线的抽屉也得做深些。","Nice choice putting the darker drawer low.","深色放下面还挺顺眼的。"),
        StudioSeed("plantstand","Window plant stand","窗边花台","Two levels give the pot its own patch of light. The lower shelf can catch a watering can or a folded cloth.","上下两层错开一小块窗光，上面摆花盆，下面还能放浇水壶或一块叠好的布。","Does the lower shelf fit a little watering can?","下层放得下小浇水壶吗？","I'd turn the taller pot toward the window.","我会把高一点的盆朝窗户放。"),
        StudioSeed("organizer","Open cubby shelf","开放格小架","The horizontal shelves go in first. Books and boxes slide in last, leaving one cubby open for whatever lands on the desk.","横板先归位，书和盒子最后横着推进去。故意留空一格，给第二天桌上冒出来的小东西。","I'd leave that middle cubby empty for my camera.","中间那个格子我想留给相机。","Watching the boxes go in last helped me read the shape.","盒子最后进去，架子的样子一下就清楚了。")
    )
    val people=listOf("Mina","Rae","Jun","Noor","Ivo","Kai","Cleo","Em","Lin","Remy","Bo","Pip","Lou","Jo","Sam")
    val studies=listOf("Cross-braced bench" to "横撑长凳","Two-level plant table" to "双层花台",
        "Cantilever reading lamp" to "悬臂阅读灯","Bedside cabinet" to "床边矮柜","Desktop book ledge" to "桌面书搁")
    private val related=listOf(0,8,1,7,4)
    private val studyRemarks=listOf(
        listOf("I keep reaching for the rail when I move a bench. This one's nicely placed." to "我搬凳子总喜欢抓横撑，这根的位置刚好。",
            "How close would your heels be to that crossbar?" to "坐下来的时候，脚后跟会碰到中间那根吗？",
            "Would put the dog's lead underneath. It's always on a door handle here." to "我家狗绳天天挂门把手上，放这凳子底下倒合适。",
            "Two slab ends and one brace. I'd draw it from this side." to "两块侧板一根撑，我画速写会选这个角度。"),
        listOf("My watering can would finally have a parking spot." to "我的浇水壶终于不用在窗台上东挪西挪了。",
            "Is the lower shelf tall enough for a second pot?" to "矮的那层还能塞一个花盆吗？",
            "The top looks quieter with just the one pot 🌱" to "上层只摆一个盆，留点空还挺好看 🌱",
            "I'd keep a saucer on the bottom shelf for repotting days." to "我会在下层备个托碟，换盆的时候随手就能拿。"),
        listOf("I'd read under the short arm, with the stem behind the book." to "我会让灯杆在书后面，短臂刚好挑到书页上方。",
            "The wide foot makes a nice spot to tap my glasses down." to "灯座边那么宽，想把眼镜顺手搁上去。",
            "That short reach suits a bedside table better than my big desk." to "这个短悬臂更适合我床头那张小桌。",
            "Is the shade wide enough for a two-page spread?" to "灯罩的宽度够照到摊开的两页吗？"),
        listOf("One drawer for cables. An open shelf for the book I'm actually reading." to "充电线塞抽屉，看到一半的书搁开放层。分工明确。",
            "That small pull gives the drawer face just enough detail." to "拉手就这么小小一截，抽屉面不显空了。",
            "Would it fit a mug above and a paperback below?" to "我想上面放杯子，下面放本书，应该能行？",
            "I like being able to see what's on the bottom level from bed." to "躺床上也能瞄见下层放了什么，这点挺好。"),
        listOf("The pencil cup on one end leaves room to pull a book out sideways." to "笔筒搁在一头，另一边抽书手不会卡着。",
            "Only two books on this one. That's about my reading pace too." to "上面就两本书，很符合我的阅读速度。",
            "That little back rail would keep things off the wall." to "后面那块小挡板正好，不用把书直接靠墙。",
            "I'd keep scissors in the cup. My pencils never stay put anyway." to "我想拿这个筒插剪刀，铅笔反正在我桌上总要跑。"))
    const val day=86_400_000L
    fun find(id:String,anchor:Long):CommunityPost? {
        val explicit=catalog.indexOfFirst { it.key==id }
        val variant=if(explicit>=0) null else id.removePrefix("study-").takeIf { id.startsWith("study-") }?.toIntOrNull()?.takeIf { it>=0 } ?: return null
        val idx=if(variant==null) explicit else related[variant%5]
        val seed=catalog[idx]
        val stamp=anchor-((variant?:idx)%90+1)*day
        val title=if(variant==null) seed.titleEn to seed.titleZh else studies[variant%5]
        val nicknames=listOf(
            listOf("The shoe bench" to "换鞋凳","Hallway perch" to "门边矮凳","Window seat" to "窗前长凳","Footrest" to "歇脚凳"),
            listOf("Windowsill plant table" to "窗边花台","Potting corner" to "阳台小花架","Watering stand" to "花盆高台","Two-tier planter" to "双层盆架"),
            listOf("Bedside reading lamp" to "床头阅读灯","Desk light" to "桌角台灯","Book light" to "书桌小灯","Nightstand lamp" to "床边灯"),
            listOf("Bedside drawer" to "床头抽屉柜","Nightstand" to "床边小柜","One-drawer table" to "单屉矮柜","Open nightstand" to "开放床头柜"),
            listOf("Desk book ledge" to "桌上小书架","Paperbacks and pens" to "书和笔的小架子","Shelf for two" to "双书搁板","Reading nook" to "读书角搁架"))
        val name=if(variant==null)title else nicknames[variant%5][(variant/5)%4]
        val remarks=if(variant==null) listOf(seed.commentEn to seed.commentZh,seed.replyEn to seed.replyZh) else {
            val options=studyRemarks[variant%5]
            val first=(variant/5)%options.size
            val second=(first+1+(variant/20)%(options.size-1))%options.size
            listOf(options[first],options[second])
        }
        val sample=remarks.mapIndexed { j,line ->
            CommunitySample(people[((variant?:idx).toLong()+3+j*4).rem(people.size).toInt()],
                line.first,line.second,stamp+day*(j+1)/4)
        }
        return CommunityPost(id,if(variant==null)seed.key else null,variant?.let { CommunityModel.example(it) },
            name.first,name.second,
            if(variant==null)seed.en else "A colour and proportion study of the ${title.first.lowercase()}. Turn it to inspect the supports and the space between them.",
            if(variant==null)seed.zh else "同一件${title.second}的配色与比例练习。转动视角，可以看到支撑件和它们之间的留空。",
            people[(variant?:idx)%people.size],stamp,8+(variant?:idx)%107,sample,false)
    }
}
