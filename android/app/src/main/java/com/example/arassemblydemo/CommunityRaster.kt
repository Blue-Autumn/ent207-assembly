package com.example.arassemblydemo

import kotlin.math.*

/**
 * Orthographic preview of a design recipe. The depth at each covered pixel determines
 * the visible part, including the inside wall of an open cup seen through its rim.
 */
internal class CommunityRaster(model: CommunityModel) {
    private data class V(val x:Float, val y:Float, val z:Float)
    private class Face(val points:List<V>, val normal:V, val color:Int)
    private class Solid(val origin:V, val faces:List<Face>)

    private val centerX = ((model.parts.minOf { it.x-it.w/2 }+model.parts.maxOf { it.x+it.w/2 })/2).toFloat()
    private val centerZ = ((model.parts.minOf { it.z-it.d/2 }+model.parts.maxOf { it.z+it.d/2 })/2).toFloat()
    private val bottom = model.parts.minOf { it.y }.toFloat()
    private val height = model.parts.maxOf { it.y+it.h }.toFloat()-bottom
    private val radius = model.parts.maxOf { hypot(abs(it.x-centerX)+it.w/2,abs(it.z-centerZ)+it.d/2) }.toFloat()
    private val pitchSin = .39f
    private val pitchCos = sqrt(1f-pitchSin*pitchSin)
    private val solids = model.parts.map { p ->
        val origin = V(p.x.toFloat()-centerX,(p.y+p.h*.5).toFloat()-bottom-height*.5f,p.z.toFloat()-centerZ)
        Solid(origin,mesh(p))
    }

    private fun mesh(part:CommunityModel.Part):List<Face> {
        val faces=ArrayList<Face>()
        val w=part.w.toFloat()/2; val h=part.h.toFloat()/2; val d=part.d.toFloat()/2
        val rgb=part.color.drop(1).toInt(16)
        fun face(normal:V,vararg points:V) {
            val len=sqrt(normal.x*normal.x+normal.y*normal.y+normal.z*normal.z)
            val light=(.77f+(.23f*normal.y+.08f*normal.z-.12f*normal.x)/len).coerceIn(.55f,1.08f)
            fun channel(shift:Int)=(((rgb shr shift and 255)*light).roundToInt()).coerceIn(0,255)
            val argb=(255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            faces+=Face(points.toList(),normal,argb)
        }
        val up=V(0f,1f,0f); val down=V(0f,-1f,0f)
        val left=V(-1f,0f,0f); val right=V(1f,0f,0f)
        val front=V(0f,0f,1f); val back=V(0f,0f,-1f)
        if (part.shape=="box" || part.shape=="wedge") {
            val a=V(-w,-h,-d); val b=V(w,-h,-d); val c=V(w,-h,d); val e=V(-w,-h,d)
            val at=a.copy(y=h); val bt=b.copy(y=h)
            face(down,a,b,c,e); face(back,a,b,bt,at)
            if (part.shape=="wedge") {
                face(V(0f,d,h),at,bt,c,e); face(left,a,at,e); face(right,b,bt,c)
            } else {
                val ct=c.copy(y=h); val et=e.copy(y=h)
                face(up,at,bt,ct,et); face(front,e,c,ct,et); face(left,a,at,et,e); face(right,b,bt,ct,c)
            }
            return faces
        }
        val axialZ=part.shape=="wheel" || part.shape=="hoop"
        val hollow=part.shape=="hoop" || part.shape=="ring"
        val positive=if(axialZ) front else up
        val negative=if(axialZ) back else down
        val segments=48
        fun at(n:Int,r:Float,end:Float):V {
            val angle=(n%segments)*2*PI/segments
            val u=cos(angle).toFloat()*r; val v=sin(angle).toFloat()*r
            return if(axialZ) V(w*u,h*v,d*end) else V(w*u,h*end,d*v)
        }
        for(n in 0 until segments) {
            val angle=(n+.5)*2*PI/segments
            val nx=cos(angle).toFloat()/w; val nv=sin(angle).toFloat()/(if(axialZ) h else d)
            val normal=if(axialZ) V(nx,nv,0f) else V(nx,0f,nv)
            val a=at(n,1f,-1f); val b=at(n+1,1f,-1f)
            val c=at(n+1,1f,1f); val e=at(n,1f,1f)
            face(normal,a,b,c,e)
            if(hollow) {
                val ai=at(n,.70f,-1f); val bi=at(n+1,.70f,-1f)
                val ci=at(n+1,.70f,1f); val ei=at(n,.70f,1f)
                face(positive,e,c,ci,ei); face(negative,a,b,bi,ai)
                face(V(-normal.x,-normal.y,-normal.z),ai,bi,ci,ei)
            } else {
                face(positive,at(n,0f,1f),e,c); face(negative,at(n,0f,-1f),a,b)
            }
        }
        return faces
    }

    /** Lifts and lateral offsets depend on where each part belongs, not its order in JSON.
     * Uniformly spreading part centres increases existing axial clearances throughout the loop. */
    fun render(target:Frame,yaw:Float=.63f,separation:Float=0f) {
        target.clear()
        val scale=min(target.width*.90f/(2*radius),target.height*.86f/(height*pitchCos+2*radius*pitchSin))
        val c=cos(yaw); val s=sin(yaw)
        val spread=1f+.29f*separation
        val sx=FloatArray(4); val sy=FloatArray(4); val sz=FloatArray(4)
        for(solid in solids) {
            val ox=solid.origin.x*spread; val oy=solid.origin.y*spread; val oz=solid.origin.z*spread
            for(face in solid.faces) {
                val normal=face.normal
                if((s*normal.x+c*normal.z)*pitchCos+normal.y*pitchSin<=0f) continue
                for(i in face.points.indices) {
                    val p=face.points[i]; val x=p.x+ox; val y=p.y+oy; val z=p.z+oz
                    val near=s*x+c*z
                    sx[i]=target.width*.5f+(c*x-s*z)*scale
                    sy[i]=target.height*.51f+(near*pitchSin-y*pitchCos)*scale
                    sz[i]=near*pitchCos+y*pitchSin
                }
                for(i in 2 until face.points.size) target.triangle(sx,sy,sz,i-1,i,face.color)
            }
        }
    }

    internal class Frame(val width:Int, val height:Int) {
        val pixels=IntArray(width*height)
        private val depth=FloatArray(width*height)

        fun clear() { pixels.fill(0); depth.fill(Float.NEGATIVE_INFINITY) }

        // Each horizontal span advances barycentric coordinates and depth by addition.
        fun triangle(x:FloatArray,y:FloatArray,z:FloatArray,b:Int,c:Int,color:Int) {
            val abx=x[b]-x[0]; val aby=y[b]-y[0]
            val acx=x[c]-x[0]; val acy=y[c]-y[0]
            val area=abx*acy-aby*acx
            if(abs(area)<.0001f)return
            val x0=ceil(minOf(x[0],x[b],x[c])-.5f).toInt().coerceAtLeast(0)
            val x1=floor(maxOf(x[0],x[b],x[c])-.5f).toInt().coerceAtMost(width-1)
            val y0=ceil(minOf(y[0],y[b],y[c])-.5f).toInt().coerceAtLeast(0)
            val y1=floor(maxOf(y[0],y[b],y[c])-.5f).toInt().coerceAtMost(height-1)
            val ux=acy/area; val uy=-acx/area; val vx=-aby/area; val vy=abx/area
            val dzb=z[b]-z[0]; val dzc=z[c]-z[0]; val dz=ux*dzb+vx*dzc
            for(row in y0..y1) {
                val dx=x0+.5f-x[0]; val dy=row+.5f-y[0]
                var u=dx*ux+dy*uy; var v=dx*vx+dy*vy
                var value=z[0]+u*dzb+v*dzc; var index=row*width+x0
                for(col in x0..x1) {
                    if(u>=-.00001f && v>=-.00001f && u+v<=1.00001f && value>depth[index]) {
                        depth[index]=value
                        pixels[index]=color
                    }
                    u+=ux; v+=vx; value+=dz; index++
                }
            }
        }
    }
}
