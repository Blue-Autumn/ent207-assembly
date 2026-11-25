package com.example.arassemblydemo

import org.json.JSONObject

/** Display-only wording cleanup. It never changes an assessment, physical action or entity identity. */
internal object AiReplyText {
    fun partDescription(id:String):String = when(id.substringBeforeLast('-')) {
        "层1板" -> "紫色层板的一半"
        "层2板" -> "蓝色层板的一半"
        "层3板" -> "肉色层板的一半"
        "层4板" -> "黄色层板的一半"
        "层5板" -> "绿色层板的一半"
        "左侧板", "右侧板" -> "灰色侧框的一半"
        "背板" -> "背板的一半"
        else -> id
    }

    fun userReference(id:String,visibleMarkerIds:List<Int>,english:Boolean=false):String {
        val visible=visibleMarkerIds.filter { it in 0..249 }.distinct().minOrNull()
        if(english){
            if(visible!=null) return "the half with tag ${visible.toString().padStart(3,'0')}"
            return when(id.substringBeforeLast('-')){
                "层1板" -> "the purple shelf half"
                "层2板" -> "the blue shelf half"
                "层3板" -> "the peach shelf half"
                "层4板" -> "the yellow shelf half"
                "层5板" -> "the green shelf half"
                "左侧板","右侧板" -> "the gray frame half"
                "背板" -> "the back panel half"
                else -> "this part"
            }
        }
        return if(visible!=null) "贴${visible.toString().padStart(3,'0')}标签的这一半" else partDescription(id)
    }

    fun prepare(value:AiReviewResult,context:JSONObject):AiReviewResult {
        val english=AiReviewEnglish(context).enabled
        val aliases=linkedMapOf<String,String>()
        val rows=listOfNotNull(context.optJSONArray("parts"),context.optJSONArray("observedParts")).flatMap { a->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        for(row in rows) {
            val id=row.optString("id");if(id.isBlank())continue
            // Only use handles supplied by the local registry, never a guessed on-screen left/right.
            val markers=row.optJSONArray("visibleMarkers")?.let { a ->
                (0 until a.length()).mapNotNull { j -> (a.opt(j) as? Number)?.toInt() }
            }.orEmpty()
            val reference=userReference(id,markers,english)
            aliases[id]=reference
            val prefix=id.substringBeforeLast('-');val half=id.substringAfterLast('-')
            if(prefix!=id&&half in listOf("1","2"))aliases["$prefix·$half"]=reference
        }
        fun clean(text:String):String {
            var result=text.trim()
            aliases.entries.sortedByDescending { it.key.length }.forEach { (from,to)->result=result.replace(from,to) }
            if(english)return result.trim()
            // Repeated sentences are a presentation duplicate, not new physical evidence.
            val seen=mutableSetOf<String>()
            return Regex("[^。！？!?；;\\n]+[。！？!?；;]?|\\n").findAll(result).map { it.value.trim() }
                .filter { it.isNotBlank() && seen.add(it.filterNot { c->c.isWhitespace()||c in "。！？!?；;" }) }
                .joinToString("")
        }
        return value.copy(headline=clean(value.headline),instruction=clean(value.instruction),
            viewRequest=clean(value.viewRequest),findings=value.findings.map(::clean).distinct())
    }

    /** viewRequest is structured capture guidance, not a second paragraph to append to the same advice. */
    fun advice(value:AiReviewResult):String = value.instruction.ifBlank { value.viewRequest }

    /** Spoken references stay ordinary; the exact tag handles remain available on screen. */
    fun spoken(value:AiReviewResult,english:Boolean=false):String {
        if(english) {
            val advice=advice(value).ifBlank { value.headline }
            return advice.replace(Regex("the half with tag [0-9]{1,3}",RegexOption.IGNORE_CASE),"this half")
        }
        val lead=when(value.assessment){"looks_ok"->"这处看着对了。";"needs_adjustment"->"先调整一下。";else->"还没看清。"}
        var text=advice(value)
        text=Regex("贴\\s*\\d{1,3}\\s*(?:号)?标签的(?:这)?一半").replace(text,"这半块板")
        text=Regex("(?:左|右)侧板[-·]?[12一二]").replace(text,"这半块侧框")
        text=Regex("层[1-5]板[-·]?[12一二]").replace(text) { partDescription(it.value.replace('·','-')) }
        text=Regex("左半[一二12]|右半[一二12]|[ABＡＢ]板").replace(text,"这半块板")
        text=text.replace("A/B面","正反面").replace("A面或B面","正反面")
        // Preserve all physical qualifications and the actual advice; never truncate midway
        // through a '先退开，再...' instruction just to meet a speaking length target.
        return if(text.startsWith(lead) || text.startsWith(value.headline) || text.startsWith("方向对了") ||
            text.startsWith("缺口方向对了"))text else lead+text
    }
}
