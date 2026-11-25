package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class AiReviewSessionTest {
    @Test fun onlyExplicitCaptureCanStartAnalysisAndDoubleTapIsIgnored(){
        val s=AiReviewSession();s.preview();assertFalse(s.accepts(s.ticket))
        val t=s.capture()!!;assertNull(s.capture());assertTrue(s.frame(t));assertFalse(s.frame(t));assertTrue(s.finish(t))
        assertFalse(s.finish(t));assertNull(s.retry())
    }
    @Test fun retakeDiscardsBothLateFrameAndLateReply(){
        val s=AiReviewSession();s.preview();val a=s.capture()!!;s.preview();assertFalse(s.frame(a))
        val b=s.capture()!!;assertTrue(s.frame(b));s.preview();assertFalse(s.finish(b))
    }
    @Test fun backgroundCannotSendAnOldFrameOrResumeAnOldReply(){
        val s=AiReviewSession();s.preview();val a=s.capture()!!;s.pause();assertFalse(s.frame(a));s.resumeRetry();assertFalse(s.accepts(a))
        val b=s.retry()!!;s.pause();s.resumeRetry();assertFalse(s.finish(b));assertFalse(s.accepts(b))
    }
    @Test fun failureNeedsExplicitRetryAndOldRequestCannotWin(){
        val s=AiReviewSession();s.preview();val a=s.capture()!!;assertTrue(s.frame(a));assertTrue(s.fail(a))
        assertFalse(s.accepts(a));val b=s.retry()!!;assertNotEquals(a,b);assertFalse(s.finish(a));assertTrue(s.finish(b))
    }
    @Test fun closingInvalidatesEverything(){
        val s=AiReviewSession();s.preview();val a=s.capture()!!;s.frame(a);s.close()
        assertFalse(s.finish(a));assertFalse(s.fail(a));assertNull(s.retry());assertNull(s.capture())
    }

    @Test fun collectingPhotosDoesNotAnalyzeUntilUserSubmits(){
        val s=AiReviewSession();s.preview();val first=s.capture()!!
        assertTrue(s.frame(first,autoAnalyze=false));assertEquals(AiReviewSession.State.READY,s.state)
        assertFalse(s.accepts(first));assertFalse(s.finish(first))
        s.preview();val second=s.capture()!!;assertTrue(s.frame(second,autoAnalyze=false))
        assertFalse(s.frame(first,false));val submitted=s.analyze()!!
        assertTrue(s.accepts(submitted));assertNull(s.analyze());assertFalse(s.finish(second));assertTrue(s.finish(submitted))
    }
    @Test fun cancelledAnalysisCanReturnToPhotosWithoutAcceptingLateReply(){
        val s=AiReviewSession();s.preview();val capture=s.capture()!!;s.frame(capture,false);val request=s.analyze()!!
        s.pause();s.resumeReady();assertFalse(s.finish(request));assertEquals(AiReviewSession.State.READY,s.state)
        val retry=s.analyze()!!;assertNotEquals(request,retry);assertTrue(s.finish(retry))
    }
}
