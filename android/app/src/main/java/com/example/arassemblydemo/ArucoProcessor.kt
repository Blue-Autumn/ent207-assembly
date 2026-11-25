package com.example.arassemblydemo

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.Objdetect
import org.opencv.objdetect.DetectorParameters
import java.nio.ByteBuffer

data class Marker(val id: Int, val points: FloatArray)
data class Detection(val markers: List<Marker>, val milliseconds: Double)
internal data class LiveDetection(val detection: Detection, val slots: List<FloatArray>, val planePreparationNanos: Long)

/** One instance, owned only by the analysis executor; never allocate a detector per frame. */
class ArucoProcessor(dictionaryId: Int = Objdetect.DICT_4X4_50) : AutoCloseable {
    companion object {
        fun initialize(): String {
            check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
            return Core.VERSION
        }
    }
    private val dictionary = Objdetect.getPredefinedDictionary(dictionaryId)
    private val detector = ArucoDetector(dictionary)
    private val subpixelDetector = ArucoDetector(dictionary,DetectorParameters().apply {set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)})
    private val gray = Mat()
    private val ids = Mat()
    private var pixels = ByteArray(0)

    fun detect(image: ImageProxy, subpixel: Boolean=false): Detection {
        require(image.format == ImageFormat.YUV_420_888) { "Expected YUV_420_888" }
        val start = System.nanoTime()
        val crop = image.cropRect
        val plane = image.planes[0]
        copyPlane(plane.buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top, crop.width(), crop.height())
        return Detection(readMarkers(gray, subpixel), (System.nanoTime() - start) / 1_000_000.0)
    }

    private fun copyPlane(buffer: ByteBuffer, stride: Int, pixelStride: Int,
                          left: Int, top: Int, width: Int, height: Int) {
        val size = width * height
        if (pixels.size != size) pixels = ByteArray(size)
        YPlaneReader.copy(buffer, stride, pixelStride, left, top, width, height, pixels)
        gray.create(height, width, CvType.CV_8UC1)
        gray.put(0, 0, pixels)
    }

    internal fun detectLive(image: ImageProxy, subpixel: Boolean, slotMarkerIds: Set<Int>): LiveDetection {
        require(image.format == ImageFormat.YUV_420_888)
        val plane = image.planes[0]
        val crop = image.cropRect
        return detectLivePlane(plane.buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top,
            crop.width(), crop.height(), subpixel, slotMarkerIds)
    }

    internal fun detectLivePlane(buffer: ByteBuffer, stride: Int, pixelStride: Int, left: Int, top: Int,
                                 width: Int, height: Int, subpixel: Boolean, slotMarkerIds: Set<Int>): LiveDetection {
        val start = System.nanoTime()
        // The borrowed image and any ROI derived from it live strictly inside this call.
        // Respect CameraX's current buffer position, sensor crop and row padding.
        val view = if (buffer.isDirect && pixelStride == 1) {
            val offset = buffer.position().toLong() + top.toLong() * stride + left
            require(left >= 0 && top >= 0 && width > 0 && height > 0 && stride >= width)
            require(offset + (height - 1L) * stride + width <= buffer.limit())
            val slice = buffer.duplicate().apply { position(offset.toInt()) }.slice()
            Mat(height, width, CvType.CV_8UC1, slice, stride.toLong())
        } else {
            copyPlane(buffer, stride, pixelStride, left, top, width, height)
            gray
        }
        val planeReady = System.nanoTime()
        try {
            val markers = readMarkers(view, subpixel)
            val detection = Detection(markers, (System.nanoTime() - start) / 1_000_000.0)
            val anchors = markers.filter { it.id in slotMarkerIds }
            val slots = if (anchors.size in 1..2) slotCandidates(view, anchors) else emptyList()
            return LiveDetection(detection, slots, planeReady - start)
        } finally {
            if (view !== gray) view.release()
        }
    }

    private fun readMarkers(image: Mat, subpixel: Boolean): List<Marker> {
        val corners = ArrayList<Mat>()
        try {
            // Avoid exporting every rejected contour as a Java Mat. The detector/parameters stay identical.
            (if(subpixel) subpixelDetector else detector).detectMarkers(image, corners, ids)
            if (corners.isEmpty()) return emptyList()
            val numbers = IntArray(corners.size)
            ids.get(0, 0, numbers)
            return corners.mapIndexed { index, mat ->
                val points = FloatArray(8)
                mat.get(0, 0, points)
                Marker(numbers[index], points)
            }
        } finally {
            corners.forEach { it.release() }
        }
    }

    /** Small, current-frame luminance ROI around identified base tags. Candidates are
     * unlabelled rectangles; FrameSlotGrid verifies them against the physical hole pattern. */
    private fun slotCandidates(gray: Mat, baseTags:List<Marker>):List<FloatArray> {
        if(baseTags.isEmpty() || gray.empty())return emptyList()
        val corners=baseTags.map { it.points }.filter { it.size==8 && it.all(Float::isFinite) }
        if(corners.isEmpty())return emptyList()
        val edge=corners.maxOf { q->(0..3).map { k->
            hypot((q[2*k]-q[2*((k+1)%4)]).toDouble(),(q[2*k+1]-q[2*((k+1)%4)+1]).toDouble())
        }.average() }
        if(edge<10.0)return emptyList()
        val radius=edge*22
        val x=max(0,(corners.minOf { q->(0..3).minOf { q[2*it] } }-radius).toInt())
        val y=max(0,(corners.minOf { q->(0..3).minOf { q[2*it+1] } }-radius).toInt())
        val r=min(gray.cols(),ceil(corners.maxOf { q->(0..3).maxOf { q[2*it] } }+radius).toInt())
        val b=min(gray.rows(),ceil(corners.maxOf { q->(0..3).maxOf { q[2*it+1] } }+radius).toInt())
        if(x>=r || y>=b)return emptyList()
        val area=gray.submat(y,b,x,r)
        val small=Mat();val smooth=Mat();val signal=Mat();val hierarchy=Mat()
        val scale=min(1.0,512.0/max(r-x,b-y))
        val result=mutableListOf<FloatArray>()
        try {
            Imgproc.resize(area,small,Size(),scale,scale,Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small,smooth,Size(0.0,0.0),4.0)
            for(light in listOf(false,true)){
                if(light)Core.subtract(small,smooth,signal) else Core.subtract(smooth,small,signal)
                Imgproc.threshold(signal,signal,16.0,255.0,Imgproc.THRESH_BINARY)
                val contours=ArrayList<MatOfPoint>()
                try{
                    Imgproc.findContours(signal,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
                    for(c in contours){
                        val box=Imgproc.boundingRect(c)
                        val longest=max(box.width,box.height)
                        if(longest<8 || longest>edge*scale*3.8 || c.rows()<4)continue
                        val points=MatOfPoint2f(*c.toArray())
                        val rect=try{Imgproc.minAreaRect(points)}finally{points.release()}
                        val long=max(rect.size.width,rect.size.height);val short=min(rect.size.width,rect.size.height)
                        if(short<1.2 || long/short !in 2.6..25.0 || Imgproc.contourArea(c)<long*short*.45)continue
                        val pq=Array(4){Point()};rect.points(pq)
                        result.add(FloatArray(8){i->if(i%2==0)(x+pq[i/2].x/scale).toFloat() else (y+pq[i/2].y/scale).toFloat()})
                        if(result.size>96)return emptyList()
                    }
                }finally{contours.forEach(Mat::release)}
            }
        }finally{hierarchy.release();signal.release();smooth.release();small.release();area.release()}
        return result
    }

    /** Retained snapshot for callers of detect(image). Live camera frames are borrowed only while open. */
    fun copyLastGray(): ByteArray = pixels.copyOf()

    override fun close() {
        gray.release()
        ids.release()
        detector.clear()
        subpixelDetector.clear()
        pixels = ByteArray(0)
        // OpenCV Java detector/dictionary wrappers are GC-managed; no public delete API.
    }
}
