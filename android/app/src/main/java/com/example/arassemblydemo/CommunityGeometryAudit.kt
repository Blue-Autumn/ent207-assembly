package com.example.arassemblydemo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Acceptance for the anchored kit. Legacy stored mesh recipes have their own reader. */
internal object CommunityGeometryAudit {
    private const val epsilon = 0.0001

    /** AABB overlap is conservatively prohibited except when a part fits inside a bore.
     * Flush seams are allowed. In an AABB
     * contact patch, test occupancy on both actual primitives so an empty bore, the
     * high side of a wedge or the corner of a circle cannot act as an invisible support.
     */
    fun accepts(parts: List<CommunityModel.Part>): Boolean {
        if(parts.size !in 2..36 || parts.minOf { it.y } < -epsilon) return false
        val neighbors=List(parts.size){ mutableListOf<Int>() }
        for(i in parts.indices) for(j in 0 until i) {
            val a=parts[i]; val b=parts[j]
            val x0=max(a.x-a.w/2,b.x-b.w/2); val x1=min(a.x+a.w/2,b.x+b.w/2)
            val y0=max(a.y,b.y); val y1=min(a.y+a.h,b.y+b.h)
            val z0=max(a.z-a.d/2,b.z-b.d/2); val z1=min(a.z+a.d/2,b.z+b.d/2)
            val dx=x1-x0; val dy=y1-y0; val dz=z1-z0
            if(dx < -epsilon || dy < -epsilon || dz < -epsilon) continue
            if(dx>epsilon && dy>epsilon && dz>epsilon) {
                if (insideBore(a,b) || insideBore(b,a)) continue
                return false
            }
            var contact=false
            val nx=if(dx>epsilon)8 else 0
            val ny=if(dy>epsilon)8 else 0
            val nz=if(dz>epsilon)8 else 0
            scan@for (ix in 0..nx) for(iy in 0..ny) for(iz in 0..nz) {
                val x=x0+ix*dx/8; val y=y0+iy*dy/8; val z=z0+iz*dz/8
                if(contains(a,x,y,z) && contains(b,x,y,z)){ contact=true; break@scan }
            }
            if(contact){ neighbors[i].add(j); neighbors[j].add(i) }
        }
        val root=parts.indexOfFirst { abs(it.y)<epsilon }
        if(root<0)return false
        val seen=mutableSetOf(root)
        val queue=ArrayDeque<Int>().apply { add(root) }
        while(queue.isNotEmpty()) for(next in neighbors[queue.removeFirst()]) {
            if(seen.add(next)) queue.add(next)
        }
        return seen.size==parts.size
    }

    /** The farthest bounding-box corner is inside the inner ellipse with a little clearance.
     * Thus every vertex of the inner object is disjoint from the shell. */
    private fun insideBore(shell:CommunityModel.Part,inner:CommunityModel.Part):Boolean {
        if(shell.shape!="ring" && shell.shape!="hoop") return false
        val x=(2*abs(inner.x-shell.x)+inner.w)/shell.w
        val cross=if(shell.shape=="ring") (2*abs(inner.z-shell.z)+inner.d)/shell.d
            else (abs(2*(inner.y-shell.y)+inner.h-shell.h)+inner.h)/shell.h
        return x*x+cross*cross<0.49-epsilon
    }

    private fun contains(p:CommunityModel.Part,x:Double,y:Double,z:Double):Boolean {
        val u=2*(x-p.x)/p.w; val v=2*(y-p.y)/p.h-1; val w=2*(z-p.z)/p.d
        if(p.shape=="box") return true // Called inside the overlapping bounding boxes only.
        if(p.shape=="wedge") return v <= -w+epsilon
        val radial=if(p.shape=="wheel" || p.shape=="hoop") u*u+v*v else u*u+w*w
        if(radial>1+epsilon)return false
        return (p.shape!="ring" && p.shape!="hoop") || radial>=0.49-epsilon
    }
}
