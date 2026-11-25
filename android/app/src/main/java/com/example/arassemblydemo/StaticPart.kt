package com.example.arassemblydemo

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.opencv.core.*
import org.opencv.calib3d.Calib3d
import kotlin.math.*

data class ProjectedPart(val points: FloatArray,val triangles: IntArray,val colors: IntArray)

/** Deliberately bounded GLB reader: our single indexed POSITION primitive, identity node, metres.
 * Uses the same distorted projection as markers. No animation, general glTF or real-world occlusion.
 */
class StaticPart(context: Context) : AutoCloseable {
    private val vertices: Array<Point3>
    private val indices: IntArray
    private val objectPoints: MatOfPoint3f
    init {
        val raw=context.assets.open("models/pin.glb").use {it.readBytes()}
        val b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int==0x46546c67 && b.int==2 && b.int==raw.size)
        val jsonLength=b.int;require(b.int==0x4e4f534a)
        val jsonBytes=ByteArray(jsonLength);b.get(jsonBytes)
        val j=JSONObject(String(jsonBytes,Charsets.UTF_8))
        require(j.getJSONObject("asset").getString("generator")=="ARAssemblyDemo single-static-mesh-v1")
        val binaryLength=b.int;require(b.int==0x004e4942);val base=b.position();require(base+binaryLength==raw.size)
        val accessors=j.getJSONArray("accessors");val views=j.getJSONArray("bufferViews")
        val positions=accessors.getJSONObject(0);val faces=accessors.getJSONObject(1)
        require(positions.getInt("componentType")==5126 && positions.getString("type")=="VEC3")
        require(faces.getInt("componentType")==5125 && faces.getString("type")=="SCALAR")
        val n=positions.getInt("count");val m=faces.getInt("count");require(n in 1..100000 && m in 3..300000 && m%3==0)
        b.position(base+views.getJSONObject(positions.getInt("bufferView")).getInt("byteOffset"))
        vertices=Array(n) {val x=b.float.toDouble();val y=b.float.toDouble();val z=b.float.toDouble();require(listOf(x,y,z).all {it.isFinite()});Point3(x*1000,-z*1000,y*1000)}
        b.position(base+views.getJSONObject(faces.getInt("bufferView")).getInt("byteOffset"))
        indices=IntArray(m) {b.int.also {require(it in 0 until n)}}
        objectPoints=MatOfPoint3f(*vertices)
    }
    fun dimensionsMm(): DoubleArray = doubleArrayOf(vertices.maxOf {it.x}-vertices.minOf {it.x},vertices.maxOf {it.y}-vertices.minOf {it.y},vertices.maxOf {it.z}-vertices.minOf {it.z})
    fun project(pose: PoseResult,model: CameraModel): ProjectedPart? {
        val rv=Mat(3,1,CvType.CV_64F);val tv=Mat(3,1,CvType.CV_64F);val k=Mat.eye(3,3,CvType.CV_64F)
        val dist=MatOfDouble(*model.distortion);val rot=Mat();val projected=MatOfPoint2f()
        try {
            rv.put(0,0,*pose.rvec);tv.put(0,0,*pose.tvec)
            k.put(0,0,model.fx);k.put(1,1,model.fy);k.put(0,2,model.cx);k.put(1,2,model.cy)
            Calib3d.Rodrigues(rv,rot);val rr=DoubleArray(9);rot.get(0,0,rr)
            val xyz=vertices.map {p -> DoubleArray(3) {i -> rr[3*i]*p.x+rr[3*i+1]*p.y+rr[3*i+2]*p.z+pose.tvec[i]} }
            if(xyz.any {it[2]<=1 || !it.all(Double::isFinite)}) return null
            Calib3d.projectPoints(objectPoints,rv,tv,k,dist,projected)
            val points=FloatArray(vertices.size*2);projected.get(0,0,points)
            if(!points.all(Float::isFinite)) return null
            val order=(0 until indices.size/3).sortedByDescending {i -> (0..2).sumOf {xyz[indices[3*i+it]][2]} }
            val triangles=IntArray(indices.size);val colors=IntArray(order.size)
            order.forEachIndexed {dest,face ->
                val a=xyz[indices[face*3]];val b=xyz[indices[face*3+1]];val c=xyz[indices[face*3+2]]
                val u=DoubleArray(3) {b[it]-a[it]};val v=DoubleArray(3) {c[it]-a[it]}
                val normal=doubleArrayOf(u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0])
                val length=sqrt(normal.sumOf {it*it}).coerceAtLeast(1e-9)
                val intensity=(85+85*abs(normal[2])/length).toInt().coerceIn(0,255)
                colors[dest]=Color.rgb(intensity,intensity,intensity)
                for(i in 0..2) triangles[dest*3+i]=indices[face*3+i]
            }
            return ProjectedPart(points,triangles,colors)
        } finally {rv.release();tv.release();k.release();dist.release();rot.release();projected.release()}
    }
    override fun close() {objectPoints.release()}
}
