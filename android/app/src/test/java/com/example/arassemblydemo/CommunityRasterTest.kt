package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class CommunityRasterTest {
    @Test fun crossingSurfacesShowTheNearestPixelOnBothSidesInEitherDrawOrder() {
        val x=floatArrayOf(1f,21f,1f)
        val y=floatArrayOf(1f,1f,21f)
        val tilted=floatArrayOf(-1f,1f,-1f)
        val flat=floatArrayOf(0f,0f,0f)
        fun draw(reverse:Boolean):IntArray {
            val frame=CommunityRaster.Frame(22,22)
            frame.clear()
            if(reverse)frame.triangle(x,y,flat,1,2,2)
            frame.triangle(x,y,tilted,1,2,1)
            if(!reverse)frame.triangle(x,y,flat,1,2,2)
            return frame.pixels
        }
        val result=draw(false)
        assertArrayEquals(result,draw(true))
        assertEquals(2,result[2*22+3])
        assertEquals(1,result[2*22+16])
    }

    @Test fun theHoleIsOpenAndTheSilhouetteStaysStableWhenPartsAreReordered() {
        fun part(shape:String,x:Double)=CommunityModel.Part(shape,x,0.0,0.0,34.0,1.0,34.0,"#719080")
        fun draw(parts:List<CommunityModel.Part>,spread:Float):IntArray {
            val frame=CommunityRaster.Frame(180,180)
            CommunityRaster(CommunityModel(parts)).render(frame,0f,spread)
            return frame.pixels
        }
        val ring=part("ring",0.0)
        assertEquals(0,draw(listOf(ring),0f)[92*180+90])
        assertNotEquals(0,draw(listOf(part("cylinder",0.0)),0f)[92*180+90])
        val parts=listOf(ring,part("box",32.0))
        assertArrayEquals(draw(parts,.72f),draw(parts.reversed(),.72f))
    }
}
