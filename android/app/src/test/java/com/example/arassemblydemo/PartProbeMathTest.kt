package com.example.arassemblydemo

import org.junit.Test
import org.junit.Assert.*

class PartProbeMathTest {
    @Test fun colorRequiresCompetitorAndRejectsAmbiguity(){
        val red=PartProbeMath.lab(0xff0000);val blue=PartProbeMath.lab(0x0000ff)
        assertEquals(100.0,PartProbeMath.lab(0xffffff)[0],.001)
        assertNull(PartProbeMath.match(red,listOf(PartProbeMath.Reference("red",red))).label)
        assertEquals("red",PartProbeMath.match(red,listOf(PartProbeMath.Reference("red",red),PartProbeMath.Reference("blue",blue))).label)
        assertNull(PartProbeMath.match(red,listOf(PartProbeMath.Reference("red",red),PartProbeMath.Reference("pink",red))).label)
    }
    @Test fun axisIsImageSpaceAndClippedShapesAreFlagged(){
        val pixels=IntArray(100*100){0xffffff}
        for(y in 45..54)for(x in 10..89)pixels[y*100+x]=0xff0000
        val r=PartProbeMath.region(pixels,100,100,50,50)
        assertEquals(800,r.pixels.size);assertEquals(0.0,r.angle,.01);assertTrue(r.elongation>2);assertFalse(r.touchesEdge)
        val vertical=IntArray(10000){0xffffff}
        for(y in 10..89)for(x in 45..54)vertical[y*100+x]=0xff0000
        assertEquals(90.0,PartProbeMath.region(vertical,100,100,50,50).angle,.01)
        assertTrue(PartProbeMath.region(IntArray(10000){0xff0000},100,100,50,50).touchesEdge)
    }
}
