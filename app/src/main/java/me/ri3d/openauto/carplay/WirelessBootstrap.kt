package me.ri3d.openauto.carplay

import java.io.Closeable
import java.io.IOException

/** Closing RFCOMM after authenticated Wi-Fi handoff is expected, even inside a blocked receive. */
internal object WirelessBootstrap {
    fun run(scope: AttemptScope, channel: Closeable, handedOff: () -> Boolean, control: () -> Unit) {
        var controlFailure: Throwable? = null
        try {
            control()
        } catch (failure: Throwable) {
            controlFailure = failure
            if (failure !is IOException || (scope.isOpen && !handedOff())) throw failure
        } finally {
            try {
                channel.close()
            } catch (failure: IOException) {
                // A cleanup error must neither end a completed handoff nor hide the first failure.
                if (controlFailure == null && scope.isOpen && !handedOff()) throw failure
            } finally {
                scope.forget(channel)
            }
        }
    }
}
