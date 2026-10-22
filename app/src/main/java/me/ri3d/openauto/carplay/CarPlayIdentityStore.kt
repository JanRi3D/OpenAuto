package me.ri3d.openauto.carplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator
import org.bouncycastle.util.encoders.Base64
import java.io.File
import java.io.IOException

/** Accessory credentials and AirPlay pairing keys are deliberately separate stores. */
class CarPlayIdentityStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("carplay-pairings", Context.MODE_PRIVATE)
    private companion object {
        // Connection setup and Receiver check create separate stores on separate threads.
        val identityLock = Any()
    }

    fun accessory(): MfiAuthenticator {
        return synchronized(identityLock) {
        val target = File(context.filesDir,"carplay-identity")
        if(!target.isDirectory) {
            val pending = File(context.filesDir,"carplay-identity-new")
            if(!pending.isDirectory && !pending.mkdirs()) throw IOException("Cannot create private identity storage")
            try {
                for(name in arrayOf(LocalMfiAuthenticator.KEY_FILE,LocalMfiAuthenticator.CERTIFICATE_FILE)) {
                    context.assets.open("carplay-mfi/$name").use { input ->
                        File(pending,name).outputStream().use { out ->
                            val buffer=ByteArray(1024);var total=0
                            while(true) {val n=input.read(buffer);if(n<0)break;total+=n
                                if(total>LocalMfiAuthenticator.MAX_FILE_LENGTH) throw IOException("Accessory identity file too large")
                                out.write(buffer,0,n)
                            }
                            if(total==0) throw IOException("Accessory identity file empty")
                        }
                    }
                }
                LocalMfiAuthenticator.load(pending)
                if(!pending.renameTo(target)) throw IOException("Cannot install private accessory identity")
            } catch(failure: Exception) {
                // No key bytes or parser exception causes are exposed to logs/UI.
                for(name in arrayOf(LocalMfiAuthenticator.KEY_FILE,LocalMfiAuthenticator.CERTIFICATE_FILE)) File(pending,name).delete()
                pending.delete()
                throw IOException("CarPlay identity unavailable. Install the standalone CarPlay build or provision a matching identity.")
            }
        }
        val local=LocalMfiAuthenticator.load(target)
        object:MfiAuthenticator {
            override fun protocolMajor()=local.protocolMajor()
            override fun readCertificate(maximumOutputLength:Int)=local.readCertificate(maximumOutputLength)
            override fun signChallenge(challenge:ByteArray)=local.signDigest(challenge)
        }
        }
    }

    fun airPlay(): AirPlayIdentity {
        return synchronized(identityLock) {
        val secret=prefs.getString("private",null)
        val public=prefs.getString("public",null)
        val id=prefs.getString("id",null)
        if(secret!=null && public!=null && id!=null) {
            try {
                val s=Base64.decode(secret);val p=Base64.decode(public)
                if(s.size==32 && p.size==32 && id.isNotEmpty() &&
                    org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(s,0).generatePublicKey().encoded.contentEquals(p)) return@synchronized AirPlayIdentity(s,p,id)
            } catch(_:RuntimeException) { }
            // Regeneration is explicit to a corrupted local pairing identity, not accessory credentials.
            prefs.edit().clear().commit()
        }
        val generated=AirPlayIdentity.generate()
        check(prefs.edit().putString("private",Base64.toBase64String(generated.privateKey))
            .putString("public",Base64.toBase64String(generated.publicKey)).putString("id",generated.pairingId).commit())
        generated
        }
    }

    fun pairings(): PairingStore {
        val store=PairingStore { id,key ->
            require(id.length<=256 && key.size==32) { "Invalid pairing identity" }
            val name="controller."+Base64.toBase64String(id.toByteArray(Charsets.UTF_8))
            synchronized(this) {
                val keys=prefs.all.keys.filter { it.startsWith("controller.") }
                require(keys.size<32 || prefs.contains(name)) { "Too many paired controllers" }
                check(prefs.edit().putString(name,Base64.toBase64String(key)).commit())
            }
        }
        val entries=prefs.all.entries.filter {it.key.startsWith("controller.")}.take(32)
        entries.forEach { entry ->
            try {
                val id=String(Base64.decode(entry.key.substringAfter("controller.")),Charsets.UTF_8)
                val key=Base64.decode(entry.value as String)
                if(id.length<=256 && key.size==32) store.save(id,key)
            } catch(_:Exception) { prefs.edit().remove(entry.key).apply() }
        }
        return store
    }
}
