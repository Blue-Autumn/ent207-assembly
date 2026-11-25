package com.example.arassemblydemo

import android.util.LruCache
import org.json.JSONArray

/** Stable local editions. Legacy study-* IDs continue to use the original furniture models. */
internal class OfflineDeskStudies(private val anchor:Long) {
    private data class Card(val title:String,val name:String,val use:String,val note:String,val reply:String,val comment:String,val palette:String)
    private val cards=listOf(
        Card("Sketch pot","速写笔筒","A banded cup keeps a few sketching tools together.","横纹沿着筒壁绕开，几支常用的笔可以摆在手边。","I'd keep my soft pencils in this one.","我想把软铅笔都放在这里。","B96846 EAD8A8 2E4940"),
        Card("Mail at the door","门边信架","The upright dividers separate mail by size.","按大小把信分开放，最矮的挡板在前面。","Where would you put the postcards?","明信片你会塞哪格？","47566D A3B7C1 E3C695"),
        Card("Wrapping desk dispenser","包装台胶带座","The roll and cutter share one base.","胶卷和小切口相对，底下是同一块底座。","Just what my wrapping drawer needs.","我放包装纸的抽屉正缺这个。","B9803D 443D39 E7A948"),
        Card("Clock by the bed","床边小钟","Two straight hands stand out against a round dial.","两根直指针压在圆表面上，放床头也能看清。","I like how spare the face is.","表面留这么空挺好的。","34587A 26313B F2E8D6"),
        Card("Recipe stand","厨房手机架","The front lip leaves the phone leaning on its backrest.","手机抵住前沿，向后靠在支撑上，适合翻菜谱。","Would it clear my case?","戴壳也放得下吗？","BB6E62 E2AE8C 454D5A"),
        Card("Headset rest","耳机搁架","A raised bar gives the headband its own spot.","把耳机横放在撑杆上，桌面就能空出来。","My monitor has been doing this job.","我的显示器一直兼着这个职。","693D58 343E4C E3B4A7"),
        Card("Tea table coasters","茶桌杯垫","The stacked discs show their edges in a narrow stripe.","圆垫依次叠起，侧面露出一道道边缘。","I'll take the top one for tea.","最顶上那片给我的茶杯。","B48958 356C7D E9C977"),
        Card("Books by the kettle","小册子书立","Upright ends frame books on a low strip.","矮底板两头立着挡板，正好放下一排小册子。","I'd leave a recipe book on the end.","我会把菜谱放最边上。","96563F B87256 E9D9AC"),
        Card("Bedside ring stand","床头戒指架","Short branches place rings at different heights.","小横杆一层层错开，摘下来的戒指有地方放。","I always lose one at the sink.","我总在水池边弄丢戒指。","315E5D C3A464 EAD7AE"),
        Card("Sketch palette","速写调色盘","Small circular wells follow an oval base.","几排圆槽落在椭圆盘上，留一格专门调灰色。","One clean well for mixing, always.","调色那格一定得留着。","EEE6D8 3869A6 D97A67"),
        Card("Countertop spices","灶边调料架","A row of lidded jars sits along a narrow rail.","带盖的小罐在窄台上排成一行。","Salt on the end for me.","盐罐我得放在最外面。","B98061 703F43 C19446"),
        Card("Propagation tubes","扦插小花器","Several tubes rise from a broad foot for individual cuttings.","竖管共用一块底座，每管可以插一枝。","Mint cuttings would go straight in 🌱","我想先插几枝薄荷 🌱","BDA994 EBE7D8 A1C7C2"),
        Card("Postcard holder","桌面明信片架","A ledge across the uprights makes space for a card.","两根立柱间架着一条横托，可以摆明信片。","There's one from Kyoto on my fridge.","我冰箱上正贴着一张京都的。","BB8C56 9D6A47 809FBE"),
        Card("Twine spool","棉线线轴","Wide end discs frame the ribbed barrel.","两端的圆片夹着中间绕线的纹路。","This beats a loose ball of string.","总比一团松掉的线好收。","78523D A44D63 D89A7F"),
        Card("Calendar for the counter","桌角台历","A block of pages sits beneath two binding loops.","页块搁在底座上，两道圈扣住顶端。","I'd mark bread day first.","第一件事，圈出烤面包的日子。","994D45 73586A F6EEDC"),
        Card("Small candle dish","小碟烛台","A short stem raises the candle above a footed dish.","烛身落在小碟中，细支脚把碟面托高。","That dark wick is easy to pick out.","烛芯那一小点很显眼。","879998 4B5B59 EFE1BB"),
        Card("Napkins at breakfast","早餐餐巾架","Two hoops keep the paper napkins standing.","两侧的圆环把餐巾托起来，方便抽取。","Mine usually live under a mug.","我家餐巾通常压在杯子底下。","5F6C7C AAB4BC D9E4E1"),
        Card("Desk pen rails","桌边笔搁","Parallel channels give each pen a separate spot.","平行的槽道让常用的笔并排放好。","One slot for the chunky marker?","最粗的那支马克笔也能放吗？","427058 A2B58B E6D8A8"),
        Card("Games night tower","桌游掷骰塔","The tower opens onto a shallow landing tray.","塔口正对一块矮挡边的托盘，骰子有地方落。","No more diving under the sofa, hopefully.","希望再也不用趴沙发底下找骰子了。","67717B 82515E C89979"),
        Card("Incense by the window","窗台香盘","A slender stick extends above the long ash plate.","一支细香伸到长盘上方，盘面用来承灰。","That's where my windowsill has room.","我窗台正好有这么一条空位。","434961 AB8A58 D4B985"),
        Card("Hanging spot for keys","钥匙挂板","Small pegs project from a flat backing.","几排短挂钉从面板伸出，钥匙可以各挂一处。","Hair ties on the spare pegs.","剩下的挂钉我拿来挂发圈。","EBE3C9 9A564B 725458"),
        Card("Lamp for a reading nook","阅读角台灯","An open cylindrical shade rests on a slim stem.","敞口的圆筒灯罩架在细杆上，适合放书桌边。","I like seeing that open rim.","喜欢能看清灯罩开口的样子。","757D56 C0AD82 F2E6CA"),
        Card("Tea for two","双杯茶托","A continuous rim outlines the shallow tray.","托盘四周绕着一圈围边，正好收住零碎小物。","Mine would fill up with teaspoons.","我这肯定会积出一堆茶匙。","D4B47D 94764B 845B71"),
        Card("Writing desk riser","书桌增高台","A raised platform leaves a clear space below.","台面整体抬高，底下留了放键盘的空间。","That's where the sketchbook can go!","这下速写本有地方摊开了。","7791AC 465E74 DCBF96")
    )
    private val cache=LruCache<Int,CommunityPost>(96)
    fun family(index:Int)=CommunityDesignKit.keys[index%cards.size]
    fun get(id:String):CommunityPost? {
        val index=id.removePrefix("local-desk-").takeIf { id.startsWith("local-desk-") }?.toIntOrNull()?.takeIf { it>=0 } ?:return null
        cache.get(index)?.let { return it }
        val card=cards[index%cards.size]
        val study=CommunityDesignKit.sample(index)
        val recipe=study.toJson()
        // These are visibly narrower or wider than the corresponding published provider edition.
        val edition=index/cards.size
        recipe.put("width",if(edition%2==0).76 else 1.29)
        recipe.put("height",if(edition%3==0).84 else if(edition%3==1)1.26 else 1.06)
        val colors=card.palette.split(' ').map { "#"+it }
        recipe.put("palette", JSONArray(if(edition%2==0)colors else listOf(colors[1],colors[0],colors[2])))
        val model=CommunityModel.fromJson(recipe) ?: study
        val people=CommunitySeeds.people
        val stamp=anchor-(index%88+2)*CommunitySeeds.day
        return CommunityPost(id,null,model,card.title,card.name,card.use,card.note,people[index%people.size],stamp,
            3+index%89,listOf(CommunitySample(people[((index%people.size)+4)%people.size],card.reply,card.comment,stamp+CommunitySeeds.day/2)),false)
            .also {cache.put(index,it)}
    }
}
