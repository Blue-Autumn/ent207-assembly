package com.example.arassemblydemo

/** Capture can collect a batch before explicit analysis. Tokens isolate retake, submit and pause. */
internal class AiReviewSession {
    enum class State { IDLE, PREVIEW, CAPTURING, READY, ANALYZING, RESULT, RETRY, PAUSED, CLOSED }
    var state=State.IDLE;private set
    private var generation=0L
    val ticket:Long get()=generation
    fun preview():Long { check(state!=State.CLOSED);generation++;state=State.PREVIEW;return generation }
    fun capture():Long? { if(state!=State.PREVIEW)return null;generation++;state=State.CAPTURING;return generation }
    fun frame(token:Long, autoAnalyze:Boolean=true):Boolean { if(token!=generation||state!=State.CAPTURING)return false;state=if(autoAnalyze)State.ANALYZING else State.READY;return true }
    fun analyze():Long? { if(state !in setOf(State.READY,State.RETRY))return null;generation++;state=State.ANALYZING;return generation }
    fun resumeReady() { check(state==State.PAUSED);state=State.READY }
    fun retry():Long? { if(state!=State.RETRY)return null;generation++;state=State.ANALYZING;return generation }
    fun finish(token:Long):Boolean { if(token!=generation||state!=State.ANALYZING)return false;state=State.RESULT;return true }
    fun fail(token:Long):Boolean { if(token!=generation||state !in setOf(State.CAPTURING,State.ANALYZING))return false;state=State.RETRY;return true }
    fun accepts(token:Long)=token==generation&&state==State.ANALYZING
    fun pause() { if(state==State.CLOSED)return;generation++;state=State.PAUSED }
    fun resumeResult() { check(state==State.PAUSED);state=State.RESULT }
    fun resumeRetry() { check(state==State.PAUSED);state=State.RETRY }
    fun close(){generation++;state=State.CLOSED}
}
