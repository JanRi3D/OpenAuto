package me.ri3d.openauto.carplay.media

import com.shilapi.xcertplay.airplay.AirPlayContact

/** Activity supplies currently down pointer IDs in the actual displayed content rectangle. */
class TouchContacts {
    data class Point(val id: Int, val x: Double, val y: Double)
    private val down = LinkedHashMap<Int,AirPlayContact>()
    @Synchronized fun update(points: List<Point>, left: Double, top: Double, width: Double, height: Double): List<AirPlayContact> {
        require(width > 0 && height > 0 && width.isFinite() && height.isFinite() && left.isFinite() && top.isFinite())
        val next = LinkedHashMap<Int,AirPlayContact>()
        points.take(2).forEach { p ->
            require(p.x.isFinite() && p.y.isFinite())
            next[p.id] = AirPlayContact(p.id, ((p.x-left)/width).coerceIn(0.0,1.0), ((p.y-top)/height).coerceIn(0.0,1.0), true)
        }
        val contacts = ArrayList<AirPlayContact>(next.values)
        down.values.filter { it.id !in next }.forEach { contacts.add(it.copy(down=false)) }
        down.clear(); down.putAll(next)
        return contacts
    }
    @Synchronized fun release(): List<AirPlayContact> {
        val released = down.values.map { it.copy(down=false) }
        down.clear()
        return released
    }
}
