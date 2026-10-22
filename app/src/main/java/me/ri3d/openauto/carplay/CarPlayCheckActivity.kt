package me.ri3d.openauto.carplay

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.view.WindowManager
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.SoftwareOpusEncoder
import java.security.SecureRandom

/** Local compatibility check. It never opens a phone connection or reports iPhone acceptance. */
class CarPlayCheckActivity:Activity() {
    private lateinit var output:TextView
    private val lines=StringBuilder()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        output=TextView(this).apply {textSize=18f;setPadding(24,24,24,24)}
        setContentView(ScrollView(this).apply {addView(output)})
        Thread({
            result("Platform API ${Build.VERSION.SDK_INT}")
            checkPart("Accessory identity") {
                val signer=CarPlayIdentityStore(this).accessory()
                check(signer.protocolMajor()==3)
                check(signer.signChallenge(ByteArray(32).also{SecureRandom().nextBytes(it)}).size==64)
                val peer=AirPlayCrypto.x25519Generate()
                val response=MfiSapAuthSetup.handle(byteArrayOf(1)+peer.publicKey,signer)
                check(response!=null && response.size>104)
            }
            checkPart("AirPlay encryption and pairing keys") {
                val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
                val message="OpenAuto CarPlay".toByteArray()
                val sealed=AirPlayCrypto.chachaSeal(key,ByteArray(12),message)
                check(AirPlayCrypto.chachaOpen(key,ByteArray(12),sealed).contentEquals(message))
                val pair=AirPlayCrypto.ed25519Generate();val signature=AirPlayCrypto.ed25519Sign(pair.privateKey,message)
                check(AirPlayCrypto.ed25519Verify(pair.publicKey,message,signature))
                val saved=CarPlayIdentityStore(this).airPlay();check(saved.privateKey.size==32 && saved.publicKey.size==32)
            }
            checkPart("Binary protocol") {
                val wire=BplistCodec.encode(linkedMapOf("name" to "OpenAuto","ok" to true))
                check((BplistCodec.decode(wire) as Map<*,*>)["ok"]==true)
                val cipher=ControlCipher(ByteArray(32),ByteArray(32))
                check(cipher.decrypt(cipher.encrypt(byteArrayOf(1,2,3))).data.contentEquals(byteArrayOf(1,2,3)))
            }
            checkPart("Software microphone codec") {
                val opus=SoftwareOpusEncoder(32000)
                try {check(opus.available);check(opus.encode(ByteArray(1920)).isNotEmpty())}finally{opus.close()}
            }
            checkPart("USB TLS loopback (synthetic)") {
                result(CarPlayTlsCheck.run())
            }
            result("CHECK COMPLETE — local checks only; connect an iPhone for projection verification")
        },"carplay-local-check").start()
    }
    private fun checkPart(name:String,block:()->Unit) {
        try {block();result("PASS: $name")}
        catch(error:Throwable){
            result("FAIL: $name (${error.javaClass.simpleName})")
            Log.e("CarPlayCheck","Local compatibility check failed: $name",error)
        }
    }
    private fun result(message:String) {
        Log.i("CarPlayCheck",message)
        runOnUiThread {lines.append(message).append('\n');output.text=lines.toString()}
    }
}
