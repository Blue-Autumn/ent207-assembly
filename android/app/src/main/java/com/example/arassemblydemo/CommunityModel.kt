package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Small, offline-readable model recipe. X and Z locate the centre; Y locates the bottom. */
internal data class CommunityModel(val parts: List<Part>, val recipe: CommunityKitRecipe? = null) {
    data class Part(val shape: String, val x: Double, val y: Double, val z: Double,
                    val w: Double, val h: Double, val d: Double, val color: String)

    fun toJson(): JSONObject {
        recipe?.let { return it.toJson() }
        val items = JSONArray()
        for (p in parts) items.put(JSONObject().put("shape", p.shape)
            .put("x", p.x).put("y", p.y).put("z", p.z)
            .put("w", p.w).put("h", p.h).put("d", p.d).put("color", p.color))
        return JSONObject().put("parts", items)
    }

    companion object {
        private val hex = Regex("#[0-9a-fA-F]{6}")
        private val shapes = setOf("box","cylinder","wheel","hoop","ring","wedge")
        fun fromJson(json: JSONObject): CommunityModel? {
            if (json.has("kit")) return CommunityDesignKit.fromJson(json)
            val array = json.optJSONArray("parts") ?: return null
            if (array.length() !in 2..36) return null
            val parts = ArrayList<Part>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: return null
                val p = Part(item.optString("shape"), item.optDouble("x"), item.optDouble("y"),
                    item.optDouble("z"), item.optDouble("w"), item.optDouble("h"),
                    item.optDouble("d"), item.optString("color"))
                if (p.shape !in shapes || !hex.matches(p.color)) return null
                if (listOf(p.w,p.h,p.d).any { it !in .1..250.0 } ||
                    listOf(p.x,p.y,p.z).any { !it.isFinite() || abs(it) > 250 }) return null
                parts.add(p)
            }
            if (!formsOneObject(parts)) return null // Older imported parts may use intersecting joints.
            return CommunityModel(parts.toList())
        }

        // Imported designs are single tabletop objects. Allow small rounding gaps at a joint,
        // but reject isolated solids that would shrink the rest of the object in its preview.
        private fun formsOneObject(parts: List<Part>): Boolean {
            val spans = listOf(
                parts.maxOf { it.x+it.w/2 } - parts.minOf { it.x-it.w/2 },
                parts.maxOf { it.y+it.h } - parts.minOf { it.y },
                parts.maxOf { it.z+it.d/2 } - parts.minOf { it.z-it.d/2 })
            val longest = spans.max()
            if (spans.min() < longest/30) return false
            val gap = (longest*.015).coerceIn(.15,1.5)
            fun touches(a: Part,b: Part) =
                abs(a.x-b.x) <= (a.w+b.w)/2+gap && abs(a.z-b.z) <= (a.d+b.d)/2+gap &&
                a.y <= b.y+b.h+gap && b.y <= a.y+a.h+gap
            val seen = mutableSetOf(0)
            val queue = ArrayDeque<Int>().apply { add(0) }
            while (queue.isNotEmpty()) {
                val part = parts[queue.removeFirst()]
                for (i in parts.indices) if (i !in seen && touches(part,parts[i])) {
                    seen += i
                    queue.add(i)
                }
            }
            return seen.size == parts.size
        }

        /** Five compact reference designs. Later indices vary the palette, spacing and handedness. */
        fun example(index: Int): CommunityModel {
            val i = index.coerceAtLeast(0)
            val edition = i / 5
            val palettes = listOf(
                listOf("#BD9475","#72918B","#E4BF80"),
                listOf("#AD806C","#879D82","#D6B8A2"),
                listOf("#D3B08A","#647985","#DDA653"),
                listOf("#9C7767","#A1AC91","#E4C99B"))
            val (wood, accent, trim) = palettes[edition % palettes.size]
            val span = 1.0 + ((edition / 4) % 5) * .07
            val sign = if (edition % 2 == 0) 1 else -1
            val parts = mutableListOf<Part>()
            fun add(shape:String,x:Int,y:Int,z:Int,w:Int,h:Int,d:Int,color:String) {
                parts += Part(shape,x*span*sign,y.toDouble(),z.toDouble(),w*span,h.toDouble(),d.toDouble(),color)
            }
            fun box(x:Int,y:Int,z:Int,w:Int,h:Int,d:Int,c:String) = add("box",x,y,z,w,h,d,c)
            fun disc(x:Int,y:Int,z:Int,w:Int,h:Int,d:Int,c:String) = add("cylinder",x,y,z,w,h,d,c)
            when (i % 5) {
                0 -> { // Slab bench with an underslung brace.
                    box(-24,0,0,6,29,28,accent); box(24,0,0,6,29,28,accent)
                    box(0,10,0,42,6,5,wood); box(0,29,0,70,5,32,wood)
                }
                1 -> { // Two-level plant table.
                    for (x in listOf(-18,18)) for (z in listOf(-13,13)) {
                        box(x,0,z,4,6,4,wood); box(x,9,z,4,31,4,wood)
                    }
                    box(0,6,0,40,3,30,accent); box(0,40,0,44,3,34,wood)
                    disc(-5,43,0,14,10,14,trim); disc(-5,53,0,12,7,12,accent)
                }
                2 -> { // Reading light with a cantilever arm.
                    disc(0,0,0,32,4,27,accent); disc(-8,4,0,4,36,4,wood)
                    box(1,40,0,22,3,4,wood); disc(10,31,0,20,9,18,trim)
                }
                3 -> { // Open-sided nightstand with a contrasting drawer.
                    box(-19,0,0,4,42,28,wood); box(19,0,0,4,42,28,wood)
                    box(0,6,0,34,3,27,wood); box(0,28,0,34,3,27,wood)
                    box(0,31,1,34,10,26,accent); box(0,34,15,10,2,2,trim)
                    box(0,42,0,44,4,30,wood)
                }
                else -> { // Book ledge with two standing books and a pencil cup.
                    box(0,0,0,62,4,24,wood); box(0,4,-10,62,22,4,accent)
                    box(-21,4,0,6,27,16,trim); box(-14,4,0,7,21,16,accent)
                    disc(16,4,0,12,14,12,trim); disc(16,18,0,2,10,2,wood)
                }
            }
            return CommunityModel(parts)
        }
    }
}
