package me.ri3d.openauto.carplay

/** Audio-only RTSP sessions must never take the screen's HID event channel. */
class PrimarySessionRegistry<T:Any>(private val token:(T)->Any?,private val closed:(T)->Boolean) {
    private val sessions=LinkedHashSet<T>()
    @Synchronized fun add(session:T) {sessions.add(session)}
    @Synchronized fun remove(session:T) {sessions.remove(session)}
    @Synchronized fun primary():T? = sessions.firstOrNull {!closed(it) && token(it)!=null}
    @Synchronized fun clear()=sessions.clear()
    @Synchronized fun size()=sessions.size
}
