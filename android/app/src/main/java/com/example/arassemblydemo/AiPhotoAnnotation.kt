package com.example.arassemblydemo

import android.graphics.*

/** Sparse identity annotation. Never fills a board hull or alters the source photograph. */
internal object AiPhotoAnnotation {
    fun make(frame:JointPhotoFrame,scope:AiReviewContext,context:android.content.Context):Bitmap {
        val copy=frame.bitmap.copy(Bitmap.Config.ARGB_8888,true)
        val canvas=Canvas(copy);val unit=(minOf(copy.width,copy.height)/540f).coerceAtLeast(0.5f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=11f*unit;typeface=Typeface.create("sans-serif",Typeface.BOLD) }
        // These are identity colors, deliberately not success/warning status colors.
        val palette=intArrayOf(Color.rgb(48,92,168),Color.rgb(119,66,147),Color.rgb(34,116,141),Color.rgb(115,83,76))
        val partOrder=scope.bindings.keys.sorted()
        val markers=frame.markers.filter { marker->val p=marker.points
            p.size==8 && p.all { it.isFinite() } && (0..3).all { p[2*it]>=0 && p[2*it]<copy.width && p[2*it+1]>=0 && p[2*it+1]<copy.height } &&
                (0..3).maxOf { p[2*it] }>(0..3).minOf { p[2*it] } && (0..3).maxOf { p[2*it+1] }>(0..3).minOf { p[2*it+1] }
        }.sortedWith(compareBy<Marker> { it.id }.thenBy { it.points[0] }.thenBy { it.points[1] })
        val texts=markers.map { marker->
            val face=scope.faces.lookup(marker.id)?.face?.takeUnless { scope.partFor(marker.id)?.startsWith("层")==true }
            val number="%03d".format(marker.id)
            val compact=number+(face?.let { " $it" }?:"")
            val full=number+" "+shortName(scope.partFor(marker.id),context)+(face?.let { "·$it" }?:"")
            full to compact
        }
        val layout=AiLabelLayout.arrange(copy.width.toDouble(),copy.height.toDouble(),markers.mapIndexed { index,marker->
            val p=marker.points
            AiLabelLayout.Label(index,AiLabelLayout.Box((0..3).minOf { p[2*it] }.toDouble(),(0..3).minOf { p[2*it+1] }.toDouble(),
                (0..3).maxOf { p[2*it] }.toDouble(),(0..3).maxOf { p[2*it+1] }.toDouble()),
                (paint.measureText(texts[index].first)+8f*unit).toDouble(),(paint.measureText(texts[index].second)+8f*unit).toDouble(),(17f*unit).toDouble())
        },(3f*unit).toDouble())
        for((index,marker) in markers.withIndex()) {
            val p=marker.points
            val part=scope.partFor(marker.id)
            val color=if(part==null)Color.DKGRAY else palette[partOrder.indexOf(part).coerceAtLeast(0)%palette.size]
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*unit;paint.color=color
            val path=Path().apply{moveTo(p[0],p[1]);for(i in 1..3)lineTo(p[2*i],p[2*i+1]);close()};canvas.drawPath(path,paint)
            val placed=layout[index];val bounds=placed.box?:continue
            // No free text rectangle means outline only; IDs and faces still travel as structured data.
            val text=if(placed.compact)texts[index].second else texts[index].first
            paint.strokeWidth=0.8f*unit
            placed.leader.zipWithNext().forEach { (a,b)->canvas.drawLine(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),paint) }
            val x=bounds.left.toFloat();val y=bounds.top.toFloat()
            paint.style=Paint.Style.FILL;paint.color=Color.argb(228,255,255,255)
            canvas.drawRoundRect(x,y,bounds.right.toFloat(),bounds.bottom.toFloat(),3*unit,3*unit,paint)
            paint.color=color;canvas.drawText(text,x+4*unit,y+12f*unit,paint)
        }
        return copy
    }
    private fun shortName(part:String?, context:android.content.Context):String {
        val label=when(part?.substringBeforeLast('-')){
            "层1板" -> R.string.marker_purple
            "层2板" -> R.string.marker_blue
            "层3板" -> R.string.marker_peach
            "层4板" -> R.string.marker_yellow
            "层5板" -> R.string.marker_green
            "左侧板","右侧板" -> R.string.marker_frame
            "背板" -> R.string.marker_back
            else -> R.string.part_unregistered
        }
        return context.getString(label)
    }
}
