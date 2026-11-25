package com.example.arassemblydemo

import org.json.JSONObject
import org.json.JSONArray

internal data class CommunityKitRecipe(val design:String, val size:List<Double>, val palette:List<String>, val count:Int) {
    fun toJson() = JSONObject().put("kit","desk-v2").put("design",design)
        .put("width",size[0]).put("height",size[1]).put("depth",size[2]).put("details",count).put("palette",JSONArray(palette))
}

/**
 * The author chooses an object, proportions, repeat count and finish. These constructors
 * own the joints, hole sizes and clearance. Solids are in a shared tabletop coordinate
 * system: x/z are the footprint centre, y is the elevation of the lower face.
 */
internal object CommunityDesignKit {
    val keys = listOf("pencil_cup","letter_sorter","tape_dispenser","desk_clock","phone_dock",
        "headphone_perch","coaster_stack","bookends","ring_tree","paint_palette","spice_rack",
        "test_tube_vase","mini_easel","bobbin_spool","desk_calendar","candle_holder","napkin_stand",
        "pen_rest","dice_tower","incense_holder","peg_board","table_lamp","serving_tray","monitor_riser")
    private val swatches = listOf(
        listOf("#C4A27F","#5E7C75","#E2B963"),listOf("#C8B5AE","#6B798A","#ECCB9D"),
        listOf("#A97757","#B5B99C","#E5CFB2"),listOf("#CFB28A","#987276","#E1BA84"))
    private val colour = Regex("#[a-fA-F0-9]{6}")

    fun fromJson(o: JSONObject): CommunityModel? {
        if (o.optString("kit") != "desk-v2") return null
        val design = o.optString("design")
        val size = listOf("width","height","depth").map { o.optDouble(it) }
        val repeat = o.optDouble("details")
        val colors = o.optJSONArray("palette") ?: return null
        if (design !in keys || size.any { it !in 0.75..1.30 } ||
            repeat !in 2.0..5.0 || repeat != repeat.toInt().toDouble() || colors.length()!=3) return null
        val palette = List(3) { colors.optString(it) }
        if (palette.any { !colour.matches(it) }) return null
        return build(design,size,palette,repeat.toInt()).takeIf { CommunityGeometryAudit.accepts(it.parts) }
    }

    fun sample(index: Int): CommunityModel {
        val i = index.coerceAtLeast(0)
        val edition = i/keys.size
        return build(keys[i%keys.size], listOf(0.9+(edition%4)*.1, 1.0+(edition%3)*.08, 1.0),
            swatches[(i+edition)%swatches.size],2+(i+edition)%4)
    }

    private fun build(key:String, scale:List<Double>, palette:List<String>, n:Int): CommunityModel {
        val pieces = mutableListOf<CommunityModel.Part>()
        val (body, accent, detail) = palette
        fun solid(shape:String,x:Number,y:Number,z:Number,w:Number,h:Number,d:Number,color:String) {
            pieces += CommunityModel.Part(shape,x.toDouble()*scale[0],y.toDouble()*scale[1],z.toDouble()*scale[2],
                w.toDouble()*scale[0],h.toDouble()*scale[1],d.toDouble()*scale[2],color)
        }
        fun box(x:Number,y:Number,z:Number,w:Number,h:Number,d:Number,c:String=body) = solid("box",x,y,z,w,h,d,c)
        fun disc(x:Number,y:Number,z:Number,w:Number,h:Number,d:Number,c:String=body) = solid("cylinder",x,y,z,w,h,d,c)
        fun ring(x:Number,y:Number,z:Number,w:Number,h:Number,d:Number,c:String=accent) = solid("ring",x,y,z,w,h,d,c)
        fun face(x:Number,y:Number,z:Number,w:Number,h:Number,d:Number,c:String) = solid("wheel",x,y,z,w,h,d,c)
        fun hoop(x:Number,y:Number,z:Number,w:Number,h:Number,d:Number,c:String) = solid("hoop",x,y,z,w,h,d,c)
        fun wedge(x:Number,y:Number,z:Number,w:Number,h:Number,d:Number,c:String) = solid("wedge",x,y,z,w,h,d,c)
        fun axis(i:Int,pitch:Int) = (i-(n-1)*.5)*pitch

        when(key) {
            "pencil_cup" -> {
                disc(0,0,0,32,3,32)
                val course=30.0/n
                repeat(n) { i ->
                    ring(0,3+i*course,0,32,course-1.5,32,body)
                    ring(0,3+(i+1)*course-1.5,0,33,1.5,33,if(i%2==0)accent else detail)
                }
                for(i in 0..1) {
                    val x=if(i==0)-4 else 3
                    box(x,3,i*3,2.5,38+i*5,2.5,if(i==0)accent else detail)
                    box(x,41+i*5,i*3,2.5,2.5,2.5,body)
                }
            }
            "letter_sorter" -> {
                val length=n*8+5
                box(0,0,0,46,4,length)
                repeat(n) { i -> box(0,4,axis(i,8),44,14+4*(n-1-i),2,if(i%2==0)accent else body) }
            }
            "tape_dispenser" -> {
                box(0,0,0,60,5,24)
                box(-10,5,-8,12,20,4,accent)
                hoop(-10,8,0,30,30,12,detail)
                box(21,5,0,8,13,17,accent)
                repeat(n) { i -> box(23,18,axis(i,3),3,2,1.5,detail) }
            }
            "desk_clock" -> {
                box(0,0,0,37,4,16)
                box(0,4,0,10,4,6,accent)
                face(0,8,0,36,36,8,body)
                face(0,9.5,4.5,33,33,1,detail)
                box(0,26,5.5,1.4,10,1,accent); box(4,25,5.5,8,1,1,accent)
                face(0,24.7,6.4,2.6,2.6,.8,body)
                for(x in listOf(-13,13)) box(x,25,5.5,2,1,1,body)
                box(0,38,5.5,1,2,1,body); box(0,12,5.5,1,2,1,body)
            }
            "phone_dock" -> {
                box(0,0,0,33,4,32)
                wedge(0,4,-3,30,24,19,accent)
                for(x in listOf(-9,9)) box(x,4,12,11,4,4,detail)
            }
            "headphone_perch" -> {
                disc(0,0,0,33,4,28,body); box(0,4,0,5,42,7,accent)
                box(0,46,0,35,5,12,body)
                for(x in listOf(-13,13)) box(x,43,0,7,3,12,detail)
            }
            "coaster_stack" -> {
                disc(0,0,0,38,3,38,body)
                repeat(n) { i -> disc(i%2,3+i*3.3,0,34,3.3,34,listOf(accent,detail,body)[i%3]) }
            }
            "bookends" -> {
                val span=n*6+18
                box(0,0,0,span,3,23,body)
                for(sign in listOf(-1,1)) box(sign*(span-3)*.5,3,0,3,24,22,accent)
                repeat(n) { i -> box(axis(i,6),3,1,5,19+(i*7)%13,16,if(i%2==0)detail else body) }
            }
            "ring_tree" -> {
                disc(0,0,0,32,4,25,body)
                box(0,4,0,4,12+n*6,4,accent)
                repeat(n) { i ->
                    val sign=if(i%2==0)-1 else 1
                    box(sign*8,10+i*6,0,12,2,3,accent)
                    box(sign*12,12+i*6,0,3,3,3,detail)
                }
            }
            "paint_palette" -> {
                val span=n*10+13
                disc(0,0,0,span,3,30,body)
                repeat(n) { i ->
                    for(sign in listOf(-1,1)) {
                        ring(axis(i,9),3,sign*5,7.5,2,7.5,body)
                        if((i+sign)%3!=0) disc(axis(i,9),3,sign*5,3.5,1.1,3.5,if(sign>0)accent else detail)
                    }
                }
            }
            "spice_rack" -> {
                box(0,0,0,n*11+6,3,18)
                box(0,3,-8,n*11+6,12,2,accent)
                repeat(n) { i ->
                    disc(axis(i,11),3,1,9,16,9,body)
                    disc(axis(i,11),19,1,10,3,10,if(i%2==0)detail else accent)
                }
            }
            "test_tube_vase" -> {
                box(0,0,0,n*12+6,5,16,body)
                repeat(n) { i -> ring(axis(i,12),5,0,9,22+6*(i%3),9,if(i%2==0)accent else detail) }
            }
            "mini_easel" -> {
                for(x in listOf(-12,12)) { box(x,0,0,5,3,23); box(x,3,-5,4,39,4) }
                box(0,9,-1,39,3,4,accent); box(0,12,-1.5,34,26,3,detail)
                box(0,36,-5,20,3,4,body)
            }
            "bobbin_spool" -> {
                disc(0,0,0,30,3,30)
                repeat(n) { i -> disc(0,3+i*4,0,21,4,21,if(i%2==0)accent else detail) }
                disc(0,3+n*4,0,30,3,30)
            }
            "desk_calendar" -> {
                box(0,0,0,44,3,22)
                for(x in listOf(-18,18)) wedge(x,3,-1,4,20,14,accent)
                box(0,3,-4,32,23,2,detail)
                for(x in listOf(-10,10)) hoop(x,26,-4,5,5,2,body)
                box(0,22,-2.7,28,2,.6,accent)
                repeat(n) { row -> for(x in listOf(-9,-3,3,9)) {
                    box(x,7+row*3,-2.7,2.7,1.2,.6,if(x==9)accent else body)
                } }
            }
            "candle_holder" -> {
                disc(0,0,0,28,3,28)
                disc(0,3,0,8,10,8,accent)
                disc(0,13,0,25,3,25)
                disc(0,16,0,15,14+n*2,15,detail)
                disc(0,30+n*2,0,1.3,3,1.3,accent)
            }
            "napkin_stand" -> {
                box(0,0,0,42,4,26)
                for(z in listOf(-9,9)) {
                    box(0,4,z,10,2,3,accent)
                    hoop(0,6,z,37,25,3,accent)
                }
                repeat(n) { i -> box(0,4,axis(i,2),32,18+i,1.3,detail) }
            }
            "pen_rest" -> {
                box(0,0,0,n*7+6,4,20,body)
                repeat(n+1) { i -> box((i-n*.5)*7,4,0,2.5,3,18,if(i%2==0)accent else detail) }
            }
            "dice_tower" -> {
                box(0,0,0,27,3,50)
                for(x in listOf(-12,12)) {
                    box(x,3,-12,3,39,23,accent)
                    box(x,3,11.5,3,4,24,body)
                }
                box(0,3,-22,21,39,3,accent)
                box(0,15,-2,21,27,3,body)
                box(0,3,22,21,4,3,detail)
            }
            "incense_holder" -> {
                box(0,0,0,58,3,12)
                disc(-21,3,0,11,4,10,accent); disc(-21,7,0,1.5,24+n,1.5,detail)
                for(z in listOf(-5,5)) box(7,3,z,40,1.4,1.4,accent)
            }
            "peg_board" -> {
                val span=n*8+8
                box(0,0,0,span+4,4,17)
                box(0,4,-2,span,29,3,accent)
                repeat(n) { i -> for(y in listOf(12,22)) face(axis(i,8),y,-.5+3,3,3,6,detail) }
            }
            "table_lamp" -> {
                disc(0,0,0,28,4,28)
                disc(0,4,0,4,25,4,accent); disc(0,29,0,27,2,27,body)
                ring(0,31,0,27,12+n,27,detail)
                disc(0,31,0,6,5,6,accent)
            }
            "serving_tray" -> {
                box(0,0,0,54,3,32)
                for(z in listOf(-15,15)) box(0,3,z,54,4,2,accent)
                for(x in listOf(-26,26)) box(x,3,0,2,7,28,body)
                for(x in listOf(-27,27)) box(x,10,0,6,2,14,detail)
            }
            "monitor_riser" -> {
                for(x in listOf(-22,22)) box(x,0,0,4,20,28,accent)
                box(0,20,0,53,4,30,body)
                box(0,12,-2,40,2,22,detail)
            }
        }
        return CommunityModel(pieces,CommunityKitRecipe(key,scale,palette,n))
    }
}
