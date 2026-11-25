package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class AiOrientationActionTest {
    private val rotation=AiOrientationAction("rotate_in_plane","层2板-2",true)
    private fun match(action:AiOrientationAction=rotation,assessment:String="needs_adjustment",kind:String="prepare",
        policy:String="layer_notch_direction",status:String="faces_unrestricted",direction:String="rotate_in_plane",
        rotationCandidate:String?="层2板-2")=action.matches(assessment,kind,policy,status,direction,"层2板-2",rotationCandidate)
    @Test fun flatPairRotationUsesOnlyTheMatchingMovingPiece(){assertTrue(match());assertFalse(match(rotationCandidate="层2板-1"));assertFalse(match(rotationCandidate=null))}
    @Test fun formerMixedFaceRuleCanNeverTriggerAFlip(){assertFalse(match(action=rotation.copy(kind="flip")));assertFalse(match(action=rotation.copy(kind="flip"),policy="same_layer_face",status="exposed_faces_differ"))}
    @Test fun ordinaryOrangeAdviceDoesNotCreateAnAction(){assertFalse(match(action=rotation.copy(kind="none")));assertFalse(match(action=rotation.copy(flatPairVisible=false)))}
    @Test fun needViewAndSuccessNeverAnimateCorrections(){assertFalse(match(assessment="need_view"));assertFalse(match(assessment="looks_ok"))}
    @Test fun finalAssemblyCannotUseFlatPairShortcut(){assertFalse(match(kind="install"));assertFalse(match(policy="cad_assembly"))}
    @Test fun missingLocalDirectionNeverAnimates(){assertFalse(match(direction="coarse_direction_ok"));assertFalse(match(direction="direction_unknown"));assertFalse(match(status="insufficient"))}
    @Test fun malformedOrUnknownActionsHaveNoVisualEffect(){for(a in listOf(rotation.copy(kind="automatic_finish"),rotation.copy(part=""),rotation.copy(part="x\n")))assertFalse(match(a))}
}
