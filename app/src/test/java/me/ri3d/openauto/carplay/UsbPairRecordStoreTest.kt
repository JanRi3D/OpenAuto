package me.ri3d.openauto.carplay

import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsbPairRecordStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun record() = LockdownPairRecord.restore("host", "buid", "00:11:22:33:44:55",
        byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5), byteArrayOf(6))

    @Test fun persistedMaterialIsPrivateToTheActualPhoneIdentifier() {
        val directory = temp.newFolder()
        UsbPairRecordStore(directory, "phone-one").save(record())
        assertNull(UsbPairRecordStore(directory, "phone-two").load())
        val loaded = UsbPairRecordStore(directory, "phone-one").load()!!
        assertEquals("host", loaded.hostId)
        assertArrayEquals(byteArrayOf(5), loaded.rootPrivateKeyPem)
        assertTrue(directory.listFiles()!!.none { it.name.contains("phone-one") })
    }

    @Test fun interruptedRotationRecoversTheLastCompleteRecord() {
        val directory = temp.newFolder()
        val store = UsbPairRecordStore(directory, "phone")
        store.save(record())
        val committed = directory.listFiles()!!.single()
        assertTrue(committed.renameTo(File(committed.path + ".bak")))
        assertArrayEquals(byteArrayOf(3), store.load()!!.hostPrivateKeyPem)
        assertTrue(committed.isFile)
    }

    @Test fun truncatedAndOversizedSavedRecordsFailClosed() {
        val directory = temp.newFolder()
        val store = UsbPairRecordStore(directory, "phone")
        store.save(record())
        val committed = directory.listFiles()!!.single()
        committed.writeBytes(byteArrayOf(0, 0, 0, 1, 1))
        assertThrows(IOException::class.java) { store.load() }
        committed.writeBytes(ByteArray(262145))
        assertThrows(IOException::class.java) { store.load() }
    }
}
