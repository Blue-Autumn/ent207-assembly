package com.example.arassemblydemo

import org.json.JSONObject
import kotlin.math.*

/** A view of the same CAD triangles used by the portfolio and guide, read from their packaged
 * scene. Positions are centred, uniformly scaled and expressed in the board's U,V,normal axes. */
internal class AiPhotoCadMesh private constructor(val points:List<Vertex>,val triangles:List<IntArray>) {
    data class Vertex(val u:Double,val v:Double,val n:Double)
    class Frame(count:Int,triangleCount:Int) {
        val x=DoubleArray(count)
        val y=DoubleArray(count)
        val z=DoubleArray(count)
        val depth=DoubleArray(triangleCount)
        val order=Array(triangleCount){it}
    }

    fun frame()=Frame(points.size,triangles.size)

    /** Positive z approaches the viewer. U and V have the same physical scale. */
    fun project(plan:AiPhotoCue.Plan,degrees:Double,out:Frame) {
        val radians=Math.toRadians(if(plan.kind=="rotate")degrees*plan.facing.parity else degrees)
        val c=cos(radians)
        val s=sin(radians)
        for((i,p) in points.withIndex()) {
            val normal=p.n*plan.faceSign
            val u:Double;val v:Double;val z:Double
            when {
                plan.kind=="rotate"->{u=p.u*c-p.v*s;v=p.u*s+p.v*c;z=normal}
                plan.axisIndex==0->{u=p.u;v=p.v*c-normal*s;z=p.v*s+normal*c}
                else->{u=p.u*c+normal*s;v=p.v;z=-p.u*s+normal*c}
            }
            val k=1.0/(1.0-.14*z)
            out.x[i]=(plan.facing.x*u-plan.facing.parity*plan.facing.y*v)*k
            out.y[i]=(plan.facing.y*u+plan.facing.parity*plan.facing.x*v)*k
            out.z[i]=z
        }
        for((i,t) in triangles.withIndex())out.depth[i]=(out.z[t[0]]+out.z[t[1]]+out.z[t[2]])/3.0
        out.order.sortBy { out.depth[it] }
    }

    companion object {
        fun readScene(source:String,requested:String,normal:Int):AiPhotoCadMesh {
            val prefix="window.SHELF_SCENE="
            require(source.startsWith(prefix))
            val rows=JSONObject(source.removePrefix(prefix).trim().removeSuffix(";")).getJSONArray("parts")
            val row=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id")==requested }
            require(normal==row.getInt("axis")&&normal in 0..2)
            val axes=(0..2).filter { it!=normal }
            val values=row.getJSONArray("verts")
            require(values.length()>0&&values.length()%9==0)
            val index=linkedMapOf<Vertex,Int>()
            val triangles=(0 until values.length() step 9).map { offset->IntArray(3){j->
                val k=offset+3*j
                val p=Vertex(values.getDouble(k+axes[0]),values.getDouble(k+axes[1]),values.getDouble(k+normal))
                index.getOrPut(p){index.size}
            }}
            val u=(index.keys.minOf { it.u }+index.keys.maxOf { it.u })/2
            val v=(index.keys.minOf { it.v }+index.keys.maxOf { it.v })/2
            val n=(index.keys.minOf { it.n }+index.keys.maxOf { it.n })/2
            val centred=index.keys.map { Vertex(it.u-u,it.v-v,it.n-n) }
            val radius=sqrt(centred.maxOf { it.u*it.u+it.v*it.v+it.n*it.n })
            require(radius.isFinite()&&radius>0)
            return AiPhotoCadMesh(centred.map { Vertex(it.u/radius,it.v/radius,it.n/radius) },triangles)
        }
    }
}
