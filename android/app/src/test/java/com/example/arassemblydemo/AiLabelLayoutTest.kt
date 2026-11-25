package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test
import com.example.arassemblydemo.AiLabelLayout.Box
import com.example.arassemblydemo.AiLabelLayout.Label
import com.example.arassemblydemo.AiLabelLayout.Placed
import com.example.arassemblydemo.AiLabelLayout.Point
import kotlin.random.Random

class AiLabelLayoutTest {
    private fun label(id:Int,x:Double,y:Double,size:Double=40.0,width:Double=82.0,compact:Double=35.0)=
        Label(id,Box(x,y,x+size,y+size),width,compact,19.0)
    private fun verify(width:Double,height:Double,input:List<Label>,output:List<Placed>,gap:Double=3.0) {
        assertEquals(input.map { it.key },output.map { it.key })
        output.forEachIndexed { index,placed ->
            val box=placed.box
            if(box==null) { assertTrue(placed.leader.isEmpty());return@forEachIndexed }
            assertTrue(box.left>=0 && box.top>=0 && box.right<=width && box.bottom<=height)
            input.forEach { assertFalse("Text covers tag",box.overlaps(it.marker.expand(gap))) }
            output.filterIndexed { i,_->i!=index }.mapNotNull { it.box }.forEach { assertFalse("Text overlaps text",box.overlaps(it.expand(gap))) }
            assertTrue(placed.leader.size>=2)
            placed.leader.zipWithNext().forEach { (a,b)->
                input.filterIndexed { i,_->i!=index }.forEach { assertFalse("Leader crosses foreign tag",AiLabelLayout.segmentIntersects(a,b,it.marker.expand(gap/2))) }
                output.filterIndexed { i,_->i!=index }.mapNotNull { it.box }.forEach { assertFalse("Leader crosses foreign text",AiLabelLayout.segmentIntersects(a,b,it.expand(gap/2))) }
            }
        }
    }
    @Test fun nearbyCodesKeepBothLabelsClearOfEachOtherAndAllCodes() {
        val input=listOf(label(32,110.0,110.0),label(108,112.0,155.0),label(43,157.0,112.0))
        val result=AiLabelLayout.arrange(400.0,360.0,input)
        verify(400.0,360.0,input,result)
        assertTrue(result.all { it.box!=null })
    }
    @Test fun allImageCornersFindAnInsidePlacement() {
        val input=listOf(label(1,0.0,0.0),label(2,360.0,0.0),label(3,0.0,320.0),label(4,360.0,320.0))
        val result=AiLabelLayout.arrange(400.0,360.0,input)
        verify(400.0,360.0,input,result)
        assertTrue(result.all { it.box!=null })
    }
    @Test fun tooLongPartNameFallsBackToCompactNumberAndFace() {
        val input=listOf(label(32,40.0,40.0,20.0,width=180.0,compact=30.0))
        val result=AiLabelLayout.arrange(100.0,100.0,input)
        verify(100.0,100.0,input,result)
        assertTrue(result.single().compact);assertNotNull(result.single().box)
    }
    @Test fun noFreePixelsLeaveOutlineOnlyRatherThanCoverTheCode() {
        val input=listOf(label(32,0.0,0.0,100.0))
        val result=AiLabelLayout.arrange(100.0,100.0,input)
        verify(100.0,100.0,input,result)
        assertNull(result.single().box)
    }
    @Test fun borderClusterAndDenseScenesStayCollisionFreeAndDeterministic() {
        val random=Random(7391)
        repeat(20) {
            val input=(0 until 24).map { i -> label(i,random.nextDouble(0.0,600.0),random.nextDouble(0.0,440.0),size=40.0) }
            val result=AiLabelLayout.arrange(640.0,480.0,input)
            verify(640.0,480.0,input,result)
            assertEquals(result,AiLabelLayout.arrange(640.0,480.0,input))
        }
    }
    @Test fun lineIntersectionDetectsCrossingWithBothEndsOutsideAndCornerTouch() {
        val box=Box(10.0,10.0,20.0,20.0)
        assertTrue(AiLabelLayout.segmentIntersects(Point(0.0,15.0),Point(30.0,15.0),box))
        assertTrue(AiLabelLayout.segmentIntersects(Point(0.0,0.0),Point(10.0,10.0),box))
        assertFalse(AiLabelLayout.segmentIntersects(Point(0.0,9.0),Point(30.0,9.0),box))
    }
    @Test fun invalidDimensionsAndAmbiguousKeysAreRejected() {
        assertThrows(IllegalArgumentException::class.java){AiLabelLayout.arrange(Double.NaN,100.0,emptyList())}
        assertThrows(IllegalArgumentException::class.java){AiLabelLayout.arrange(100.0,100.0,listOf(label(1,0.0,0.0),label(1,50.0,50.0)))}
        assertThrows(IllegalArgumentException::class.java){AiLabelLayout.arrange(100.0,100.0,listOf(label(1,-1.0,0.0)))}
        assertEquals(emptyList<Placed>(),AiLabelLayout.arrange(100.0,100.0,emptyList()))
    }
}
