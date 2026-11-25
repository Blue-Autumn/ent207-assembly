package com.example.arassemblydemo

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommunityModelTest {
    @Test fun allReferenceShapesAndLaunchDesignsPassTheRenderingGate() {
        for (i in 0..49) {
            val model = CommunityModel.example(i)
            assertEquals(model,CommunityModel.fromJson(model.toJson()))
        }
        val fixture = sequenceOf("src/main/assets/community/launch.json","app/src/main/assets/community/launch.json")
            .map(::File).first { it.isFile }
        val batch = JSONArray(fixture.readText())
        assertTrue(batch.length() >= 2)
        for(i in 0 until batch.length()) assertNotNull(CommunityModel.fromJson(batch.getJSONObject(i).getJSONObject("model")))
    }

    @Test fun disconnectedSolidsAndUnsupportedShapesFallBack() {
        val model = CommunityModel.example(0).toJson()
        val first = model.getJSONArray("parts").getJSONObject(0)
        first.put("x",-200)
        assertNull(CommunityModel.fromJson(model))
        first.put("x",-24).put("shape","script")
        assertNull(CommunityModel.fromJson(model))
    }

    @Test fun everyDesignBuildsAtBothSizeLimitsAndEveryDetailCount() {
        val silhouettes=mutableSetOf<String>()
        for((i,design) in CommunityDesignKit.keys.withIndex()) {
            val sample=CommunityDesignKit.sample(i)
            for(n in 2..5) for(aspect in listOf(.75,1.30)) {
                val recipe=sample.toJson().put("width",aspect).put("height",if(aspect==.75) 1.30 else .75)
                    .put("depth",aspect).put("details",n)
                val rebuilt=CommunityModel.fromJson(recipe)
                assertNotNull("Rejected $design ($n details, scale $aspect)",rebuilt)
                assertEquals(rebuilt,CommunityModel.fromJson(rebuilt!!.toJson()))
                assertFalse(rebuilt.toJson().has("parts"))
            }
            val signature=sample.parts.joinToString { "${it.shape}/${it.x}/${it.y}/${it.z}/${it.w}/${it.h}/${it.d}" }
            assertTrue("Repeated geometry in $design",silhouettes.add(signature))
        }
        assertEquals(24,silhouettes.size)
    }

    @Test fun deskKitRejectsOverlapAndFalseSupports() {
        val box=CommunityModel.Part("box",0.0,0.0,0.0,10.0,10.0,10.0,"#ABABAB")
        assertFalse(CommunityGeometryAudit.accepts(listOf(box,box.copy(y=9.0))))
        assertFalse(CommunityGeometryAudit.accepts(listOf(box,box.copy(y=11.0))))
        val ring=box.copy(shape="ring")
        assertFalse(CommunityGeometryAudit.accepts(listOf(ring,box.copy(y=10.0,w=1.0,d=1.0))))
        assertTrue(CommunityGeometryAudit.accepts(listOf(ring,box.copy(y=10.0))))
        assertNull(CommunityModel.fromJson(CommunityDesignKit.sample(0).toJson().put("details",2.8)))
        assertNull(CommunityModel.fromJson(CommunityDesignKit.sample(1).toJson().put("height",900)))
    }
}
