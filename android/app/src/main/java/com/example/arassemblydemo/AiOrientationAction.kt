package com.example.arassemblydemo

/** A scene animation requires an explicit visual instruction, not merely an orange AI result. */
internal data class AiOrientationAction(val kind:String,val part:String,val flatPairVisible:Boolean) {
    val valid:Boolean get()=flatPairVisible&&kind in setOf("flip","rotate_in_plane")&&part.length in 1..128&&part.none { it.code<32 }
    fun matches(assessment:String,taskKind:String,policy:String,status:String,directionStatus:String,
        flipCandidate:String?,rotationCandidate:String?):Boolean = valid&&assessment=="needs_adjustment"&&
        taskKind=="prepare"&&policy=="layer_notch_direction"&&when(kind) {
            "rotate_in_plane" -> status=="faces_unrestricted"&&directionStatus=="rotate_in_plane"&&part==rotationCandidate
            else -> false
        }
}
