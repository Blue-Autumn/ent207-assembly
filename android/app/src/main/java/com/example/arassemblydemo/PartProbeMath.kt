package com.example.arassemblydemo

import kotlin.math.*

/** An assisted image-space baseline, deliberately not a part classifier or 6DoF estimator. */
object PartProbeMath {
    data class Reference(val label: String, val lab: DoubleArray)
    data class Match(val label: String?, val distance: Double, val margin: Double)
    data class Region(val pixels: IntArray, val cx: Double, val cy: Double,
        val angle: Double, val elongation: Double, val touchesEdge: Boolean)
    fun lab(rgb: Int): DoubleArray {
        fun linear(x: Int): Double { val s=x/255.0; return if(s<=0.04045)s/12.92 else ((s+0.055)/1.055).pow(2.4) }
        val r=linear(rgb shr 16 and 255);val g=linear(rgb shr 8 and 255);val b=linear(rgb and 255)
        fun f(t:Double)=if(t>216.0/24389)t.pow(1.0/3) else t*(24389.0/27)/116+16.0/116
        val x=f((.4124564*r+.3575761*g+.1804375*b)/.95047)
        val y=f(.2126729*r+.7151522*g+.072175*b)
        val z=f((.0193339*r+.119192*g+.9503041*b)/1.08883)
        return doubleArrayOf(116*y-16,500*(x-y),200*(y-z))
    }
    fun distance(a:DoubleArray,b:DoubleArray)=sqrt(a.indices.sumOf{(a[it]-b[it]).pow(2)})
    fun patch(rgb:IntArray,w:Int,h:Int,x:Int,y:Int):DoubleArray {
        require(rgb.size==w*h && x in 0 until w && y in 0 until h)
        val labs=ArrayList<DoubleArray>()
        for(j in max(0,y-3)..min(h-1,y+3))for(i in max(0,x-3)..min(w-1,x+3))labs+=lab(rgb[j*w+i])
        return DoubleArray(3){k->labs.map{it[k]}.sorted()[labs.size/2]}
    }
    fun match(sample:DoubleArray,refs:List<Reference>):Match {
        // Competing labels, not adjacent samples of the same label, determine ambiguity.
        val ranked=refs.groupBy{it.label}.map{(label,items)->label to items.minOf{distance(sample,it.lab)}}.sortedBy{it.second}
        if(ranked.size<2)return Match(null,ranked.firstOrNull()?.second?:-1.0,0.0)
        val best=ranked[0];val margin=ranked[1].second-best.second
        return Match(if(best.second<=20&&margin>=5)best.first else null,best.second,margin)
    }
    fun region(rgb:IntArray,w:Int,h:Int,x:Int,y:Int,tolerance:Double=18.0):Region {
        require(rgb.size==w*h&&x in 0 until w&&y in 0 until h)
        val seed=patch(rgb,w,h,x,y);val seen=BooleanArray(rgb.size);val queue=IntArray(rgb.size)
        var n=1;var read=0;queue[0]=y*w+x;seen[queue[0]]=true
        fun add(i:Int){if(!seen[i]){seen[i]=true;if(distance(lab(rgb[i]),seed)<=tolerance)queue[n++]=i}}
        while(read<n){val i=queue[read++];val px=i%w;val py=i/w;if(px>0)add(i-1);if(px<w-1)add(i+1);if(py>0)add(i-w);if(py<h-1)add(i+w)}
        var sx=0.0;var sy=0.0;var edge=false
        for(i in 0 until n){val px=queue[i]%w;val py=queue[i]/w;sx+=px;sy+=py;edge=edge||px==0||py==0||px==w-1||py==h-1}
        val cx=sx/n;val cy=sy/n;var xx=0.0;var yy=0.0;var xy=0.0
        for(i in 0 until n){val dx=queue[i]%w-cx;val dy=queue[i]/w-cy;xx+=dx*dx;yy+=dy*dy;xy+=dx*dy}
        val spread=sqrt((xx-yy).pow(2)+4*xy*xy)
        val ratio=(xx+yy+spread)/max(1e-9,xx+yy-spread)
        val angle=((atan2(2*xy,xx-yy)*90/PI)+180)%180
        return Region(queue.copyOf(n),cx,cy,angle,ratio,edge)
    }
}
