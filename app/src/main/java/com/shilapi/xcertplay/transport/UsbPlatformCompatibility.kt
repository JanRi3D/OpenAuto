// Adapted from DiPlay 143f4674b60b564a16baae5110b3b90fb7277631 (GPL-3.0). See THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay.transport

import android.annotation.TargetApi
import android.hardware.usb.*
import android.os.Build
import java.io.IOException

/** No UsbConfiguration reference is exposed to API 16 class loading. */
class UsbConfigurationSnapshot internal constructor(
    val id: Int,
    private val interfaces: List<UsbInterface>,
    private val alternates: Map<UsbInterface, Int>,
) {
    val interfaceCount: Int get() = interfaces.size
    fun getInterface(index: Int): UsbInterface = interfaces[index]
    fun alternateSetting(iface: UsbInterface): Int = alternates[iface] ?: 0
}

object UsbPlatformCompatibility {
    fun configurations(device: UsbDevice, connection: UsbDeviceConnection? = null): List<UsbConfigurationSnapshot> {
        if (Build.VERSION.SDK_INT >= 21) return Api21.configurations(device)
        if (connection == null) return emptyList()
        val raw = LegacyUsbDescriptorPolicy.parse(connection.rawDescriptors ?: return emptyList())
        val active = activeConfiguration(connection) ?: throw IOException("Cannot verify active USB configuration on API 16-20")
        val config = raw.singleOrNull { it.id == active }
            ?: throw IOException("Active USB configuration is absent from raw descriptors")
        val framework = (0 until device.interfaceCount).map(device::getInterface)
        val mapped = linkedMapOf<UsbInterface, Int>()
        for (iface in framework) {
            val endpoints = (0 until iface.endpointCount).map { index ->
                val endpoint = iface.getEndpoint(index)
                LegacyUsbDescriptorPolicy.Endpoint(endpoint.address, endpoint.attributes, endpoint.maxPacketSize)
            }
            val matches = config.interfaces.filter {
                LegacyUsbDescriptorPolicy.matches(it, iface.id, iface.interfaceClass, iface.interfaceSubclass,
                    iface.interfaceProtocol, endpoints)
            }
            if (matches.size != 1) throw IOException("USB framework endpoints do not uniquely match active descriptors; unsupported on API 16-20")
            mapped[iface] = matches.single().alternateSetting
        }
        if (mapped.isEmpty()) throw IOException("No verified framework USB interfaces")
        return listOf(UsbConfigurationSnapshot(active, framework, mapped))
    }

    fun selectConfiguration(connection: UsbDeviceConnection, device: UsbDevice, configuration: UsbConfigurationSnapshot): Boolean {
        if (Build.VERSION.SDK_INT >= 21) return Api21.selectConfiguration(connection, device, configuration.id) &&
            activeConfiguration(connection) == configuration.id
        // Only the already exposed configuration is allowed. Switching would leave stale framework endpoints.
        if (activeConfiguration(connection) != configuration.id) return false
        return configurations(device, connection).singleOrNull()?.id == configuration.id
    }

    fun alternateSetting(iface: UsbInterface): Int = if (Build.VERSION.SDK_INT >= 21) Api21.alternateSetting(iface) else 0

    fun selectInterface(connection: UsbDeviceConnection, iface: UsbInterface, alternate: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 21) return Api21.selectInterface(connection, iface)
        val active = activeConfiguration(connection) ?: return false
        val raw = LegacyUsbDescriptorPolicy.parse(connection.rawDescriptors ?: return false)
            .singleOrNull { it.id == active } ?: return false
        val endpoints = (0 until iface.endpointCount).map { index ->
            val endpoint = iface.getEndpoint(index)
            LegacyUsbDescriptorPolicy.Endpoint(endpoint.address, endpoint.attributes, endpoint.maxPacketSize)
        }
        val verified = raw.interfaces.filter {
            it.alternateSetting == alternate && LegacyUsbDescriptorPolicy.matches(it, iface.id,
                iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol, endpoints)
        }
        if (verified.size != 1) return false
        val selected = ByteArray(1)
        fun current(): Int? = if (connection.controlTransfer(0x81, 10, 0, iface.id, selected, 1, 1000) == 1)
            selected[0].toInt() and 255 else null
        if (current() == alternate) return true
        if (connection.controlTransfer(0x01, 11, alternate, iface.id, null, 0, 1000) != 0) return false
        return current() == alternate
    }

    fun activeConfiguration(connection: UsbDeviceConnection): Int? {
        val value = ByteArray(1)
        return if (connection.controlTransfer(0x80, 8, 0, 0, value, 1, 1000) == 1) value[0].toInt() and 255 else null
    }

    @TargetApi(21)
    private object Api21 {
        fun configurations(device: UsbDevice): List<UsbConfigurationSnapshot> =
            (0 until device.configurationCount).map { index ->
                val config = device.getConfiguration(index)
                val interfaces = (0 until config.interfaceCount).map(config::getInterface)
                UsbConfigurationSnapshot(config.id, interfaces, interfaces.associateWith { it.alternateSetting })
            }
        fun selectConfiguration(connection: UsbDeviceConnection, device: UsbDevice, id: Int): Boolean =
            (0 until device.configurationCount).map(device::getConfiguration).firstOrNull { it.id == id }
                ?.let(connection::setConfiguration) ?: false
        fun alternateSetting(iface: UsbInterface): Int = iface.alternateSetting
        fun selectInterface(connection: UsbDeviceConnection, iface: UsbInterface): Boolean = connection.setInterface(iface)
    }
}
