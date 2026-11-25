package com.example.arassemblydemo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssemblyProgressSummaryTest {
    private val flow=JSONObject("""{"preparations":["prepare:a","prepare:b"],"tasks":[{"id":"prepare:a","requires":[],"title":"A"},{"id":"prepare:b","requires":[],"title":"B"},{"id":"install:a","requires":["prepare:a"],"title":"装A"}]}""")
    private fun record(done:String="[\"prepare:a\"]",scope:String="owner:rev",active:String="install:a")="""{"storageKey":"$scope","done":$done,"active":"$active"}"""
    @Test fun summaryUsesRealGraphCounts(){val s=AssemblyProgressSummary.parse(record(),"owner","owner:rev",flow);assertEquals(1,s.done);assertEquals(3,s.total);assertEquals(1,s.prepared);assertEquals(2,s.preparationTotal);assertEquals("装A",s.activeTitle)}
    @Test fun independentPreparationIsValid(){assertEquals(1,AssemblyProgressSummary.parse(record("[\"prepare:b\"]"),"owner","owner:rev",flow).prepared)}
    @Test fun rejectsOtherOwnerOrRegistration(){assertThrows(IllegalArgumentException::class.java){AssemblyProgressSummary.parse(record(scope="other:rev"),"owner","owner:rev",flow)}}
    @Test fun rejectsMissingDependency(){assertThrows(IllegalArgumentException::class.java){AssemblyProgressSummary.parse(record("[\"install:a\"]"),"owner","owner:rev",flow)}}
    @Test fun rejectsUnknownOrDuplicateTask(){for(ids in listOf("[\"wrong\"]","[\"prepare:a\",\"prepare:a\"]"))assertThrows(IllegalArgumentException::class.java){AssemblyProgressSummary.parse(record(ids),"owner","owner:rev",flow)}}
    @Test fun rejectsUnknownActive(){assertThrows(IllegalArgumentException::class.java){AssemblyProgressSummary.parse(record(active="unknown"),"owner","owner:rev",flow)}}
}
