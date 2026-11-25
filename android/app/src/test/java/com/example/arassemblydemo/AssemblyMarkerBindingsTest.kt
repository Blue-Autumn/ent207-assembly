package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class AssemblyMarkerBindingsTest {
    private val parts=setOf("层1板-1","层1板-2")
    @Test fun fourPerFaceAreStillOnePhysicalPart() {
        val result=AssemblyMarkerBindings.validate((0..7).map { AssemblyBinding("层1板-1",it,100+it) },parts)
        assertEquals(1,result.size);assertEquals((100..107).toList(),result["层1板-1"])
    }
    @Test fun fullLayerHasSixteenUniqueTags() {
        val entries=parts.toList().flatMapIndexed { i,p->(0..7).map { AssemblyBinding(p,it,i*8+it) } }
        val result=AssemblyMarkerBindings.validate(entries,parts)
        assertEquals(2,result.size);assertEquals(16,result.values.flatten().size)
    }
    @Test fun highNumberRequiresExplicitBinding() {
        assertEquals(listOf(249),AssemblyMarkerBindings.validate(listOf(AssemblyBinding("层1板-1",0,249)),parts)["层1板-1"])
        assertTrue(AssemblyMarkerBindings.validate(emptyList(),parts).isEmpty())
    }
    @Test fun duplicatesInvalidPartsAndRangesFail() {
        val bad=listOf(listOf(AssemblyBinding("层1板-1",0,4),AssemblyBinding("层1板-2",1,4)),
            listOf(AssemblyBinding("层1板-1",0,4),AssemblyBinding("层1板-1",0,5)),
            listOf(AssemblyBinding("missing",0,4)),listOf(AssemblyBinding("层1板-1",8,4)),listOf(AssemblyBinding("层1板-1",0,250)))
        bad.forEach { rows->try { AssemblyMarkerBindings.validate(rows,parts);fail("must reject") } catch (_:IllegalArgumentException) {} }
    }
}
