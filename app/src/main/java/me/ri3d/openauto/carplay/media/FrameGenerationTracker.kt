package me.ri3d.openauto.carplay.media

/** A cumulative decoder counter must advance after this screen's post-close baseline. */
internal class FrameGenerationTracker {
    private var baseline=0L
    private var waiting=false

    @Synchronized fun begin(renderedAfterPreviousClose: Long) {
        baseline=renderedAfterPreviousClose
        waiting=true
    }

    @Synchronized fun end() { waiting=false }

    @Synchronized fun consume(rendered: Long): Boolean {
        if(!waiting || rendered<=baseline)return false
        waiting=false
        return true
    }
}
