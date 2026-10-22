package me.ri3d.openauto.carplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.*
import android.widget.*
import me.ri3d.openauto.Perms
import me.ri3d.openauto.R
import me.ri3d.openauto.carplay.media.TouchContacts
import me.ri3d.openauto.settings.Prefs
import me.ri3d.openauto.ui.Dialogs
import me.ri3d.openauto.ui.Insets

/** Setup and an undecorated projection surface using OpenAuto's existing projection layout. */
class CarPlayActivity:Activity(),CarPlayManager.Listener,SurfaceHolder.Callback {
    private lateinit var manager:CarPlayManager
    private lateinit var stage:FrameLayout
    private lateinit var surfaceView:SurfaceView
    private lateinit var setup:ScrollView
    private lateinit var status:TextView
    private lateinit var ssid:EditText
    private lateinit var password:EditText
    private lateinit var phones:Spinner
    private lateinit var hotspot:CheckBox
    private lateinit var wireless:Button
    private lateinit var wired:Button
    private lateinit var disconnect:Button
    private var addresses=emptyList<String>()
    private val contacts=TouchContacts()
    private var contentLeft=0
    private var contentTop=0
    private var contentWidth=1
    private var contentHeight=1

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        manager=CarPlayManager.get(this)
        setContentView(R.layout.activity_projection)
        if(Prefs(this).keepScreenOn())window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Insets.systemBars(window)
        stage=findViewById(R.id.stage)
        status=findViewById(R.id.status)
        surfaceView=SurfaceView(this)
        stage.addView(surfaceView,FrameLayout.LayoutParams(-1,-1))
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener {_,event->onTouch(event)}
        stage.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->layoutVideo()}
        findViewById<View>(R.id.close).setOnClickListener {manager.stop();finish()}
        buildSetup()
        if(Build.VERSION.SDK_INT>=33)Api33.register(this){manager.stop();finish()}
    }
    private fun buildSetup() {
        setup=ScrollView(this).apply {setBackgroundColor(0xf5222222.toInt());isFillViewport=true}
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(20),dp(24),dp(20))}
        setup.addView(box)
        fun label(text:String,size:Float=18f)=TextView(this).apply {this.text=text;textSize=size;setTextColor(Color.WHITE);setPadding(0,dp(6),0,dp(6))}
        box.addView(label("CarPlay",28f))
        box.addView(label("Connect your iPhone by USB, or pair it with Android Bluetooth and use Wi-Fi.",16f))
        phones=Spinner(this);box.addView(phones)
        hotspot=CheckBox(this).apply {text="Start radio hotspot";setTextColor(Color.WHITE);isChecked=manager.settings.getBoolean("hotspot",false)}
        box.addView(hotspot)
        val wifi=applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION") val current=runCatching{wifi?.connectionInfo?.ssid?.trim('"')}.getOrNull()
        ssid=EditText(this).apply {hint="Wi-Fi name";isSingleLine=true
            setText(manager.settings.getString("ssid",current?.takeUnless{it=="<unknown ssid>"} ?: "OpenAuto"))}
        password=EditText(this).apply {hint="Wi-Fi password (blank for an open network)";isSingleLine=true
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setText(manager.settings.getString("password",""))}
        box.addView(ssid);box.addView(password)
        box.addView(label("With the hotspot off, connect both devices to the Wi-Fi network above.",15f))
        val actions=LinearLayout(this)
        wireless=Button(this).apply {text="Wireless";setOnClickListener {connectWireless()}}
        wired=Button(this).apply {text="USB";setOnClickListener {connectWired()}}
        disconnect=Button(this).apply {text="Disconnect";setOnClickListener {manager.stop()}}
        for(button in arrayOf(wireless,wired,disconnect))actions.addView(button,LinearLayout.LayoutParams(0,dp(52),1f))
        box.addView(actions)
        val more=LinearLayout(this)
        more.addView(Button(this).apply{text="Siri";setOnClickListener{manager.siri()}},LinearLayout.LayoutParams(0,dp(48),1f))
        more.addView(Button(this).apply{text="Refresh phones";setOnClickListener{refreshPhones()}},LinearLayout.LayoutParams(0,dp(48),1f))
        more.addView(Button(this).apply{text="Connection log";setOnClickListener{Dialogs.info(this@CarPlayActivity,"CarPlay connection",manager.logLines().joinToString("\n"))}},LinearLayout.LayoutParams(0,dp(48),1f))
        box.addView(more)
        box.addView(Button(this).apply {text="Receiver check";setOnClickListener{startActivity(Intent(this@CarPlayActivity,CarPlayCheckActivity::class.java))}})
        stage.addView(setup,FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels-dp(32),dp(700)),-2,Gravity.CENTER))
        refreshPhones()
    }
    @SuppressLint("MissingPermission") // Paired-device read is guarded by Perms.hasBluetooth.
    private fun refreshPhones() {
        if(!Perms.hasBluetooth(this)) {addresses=emptyList();phones.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Allow Bluetooth to select your iPhone"));return}
        val bt=BluetoothAdapter.getDefaultAdapter()
        val devices=runCatching{bt?.bondedDevices?.toList()?.sortedBy{it.name ?: it.address}.orEmpty()}.getOrDefault(emptyList())
        addresses=devices.map{it.address}
        phones.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            devices.map{(it.name ?: "Paired device")+" · "+it.address.takeLast(5)}.ifEmpty{listOf("Pair your iPhone in Android Bluetooth settings first")})
        val selected=addresses.indexOf(manager.settings.getString("phone",null));if(selected>=0)phones.setSelection(selected)
    }
    private fun permissions(wifi:Boolean):Boolean {
        if(wifi && !Perms.hasBluetooth(this)){Perms.requestBluetooth(this);return false}
        if(wifi && hotspot.isChecked && !Perms.hasHotspot(this)){Perms.requestHotspot(this);return false}
        if(Prefs(this).mic() && !Perms.hasMic(this)){Perms.requestMic(this);return false}
        return true
    }
    @SuppressLint("MissingPermission") // permissions(true) checks the runtime Bluetooth grant first.
    private fun connectWireless() {
        if(!permissions(true))return
        val bt=BluetoothAdapter.getDefaultAdapter()
        @SuppressLint("MissingPermission") val enabled=bt?.isEnabled==true
        if(bt!=null && !enabled) {startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),1001);return}
        val phone=addresses.getOrNull(phones.selectedItemPosition)
        if(phone==null){Dialogs.info(this,"CarPlay","Pair and select your iPhone in Android Bluetooth settings, then refresh the phone list.");return}
        manager.settings.edit().putString("ssid",ssid.text.toString()).putString("password",password.text.toString())
            .putString("phone",phone).putBoolean("hotspot",hotspot.isChecked).apply()
        try {manager.startWireless(ssid.text.toString(),password.text.toString(),phone,hotspot.isChecked,stage.width,stage.height)}
        catch(e:IllegalArgumentException){Dialogs.info(this,"CarPlay",e.message ?: "Check the Wi-Fi settings")}
    }
    private fun connectWired() {
        if(!permissions(false))return
        val consent=VpnService.prepare(this)
        if(consent==null){manager.startWired(stage.width,stage.height);return}
        // Some radio firmwares leave out the system's VPN confirmation screen.
        try {startActivityForResult(consent,1002)}
        catch(e:ActivityNotFoundException){Dialogs.info(this,"CarPlay","This radio has no VPN confirmation screen, which USB CarPlay needs. Use the wireless connection instead.")}
    }
    @Deprecated("Platform callback retained for API 16")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode==RESULT_OK)when(requestCode){1001->{refreshPhones();connectWireless()};1002->manager.startWired(stage.width,stage.height)}
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);refreshPhones()
    }
    override fun onStart(){super.onStart();manager.addListener(this)}
    override fun onStop(){manager.touch(contacts.release());manager.setSurface(null);manager.removeListener(this);super.onStop()}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(!hasFocus && ::manager.isInitialized)manager.touch(contacts.release());if(hasFocus)Insets.systemBars(window)}
    override fun onCarPlayChanged() {
        status.text=manager.status
        setup.visibility=if(manager.projecting)View.GONE else View.VISIBLE
        status.visibility=if(manager.projecting)View.GONE else View.VISIBLE
        wireless.isEnabled=!manager.busy;wired.isEnabled=!manager.busy;disconnect.isEnabled=manager.busy
        layoutVideo()
    }
    private fun layoutVideo() {
        if(stage.width<=0 || stage.height<=0)return
        val ratio=manager.displayWidth.toDouble()/manager.displayHeight
        contentWidth=stage.width;contentHeight=(contentWidth/ratio).toInt()
        if(contentHeight>stage.height){contentHeight=stage.height;contentWidth=(contentHeight*ratio).toInt()}
        contentWidth=contentWidth.coerceAtLeast(1);contentHeight=contentHeight.coerceAtLeast(1)
        contentLeft=(stage.width-contentWidth)/2;contentTop=(stage.height-contentHeight)/2
        val p=surfaceView.layoutParams as FrameLayout.LayoutParams
        if(p.width!=contentWidth || p.height!=contentHeight){p.width=contentWidth;p.height=contentHeight;p.gravity=Gravity.CENTER;surfaceView.layoutParams=p}
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun onTouch(event:MotionEvent):Boolean {
        if(!manager.projecting)return true
        if(event.actionMasked==MotionEvent.ACTION_CANCEL){manager.touch(contacts.release());return true}
        val excluded=if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_POINTER_UP)event.actionIndex else -1
        val points=(0 until event.pointerCount).filter{it!=excluded}.map{TouchContacts.Point(event.getPointerId(it),event.getX(it).toDouble(),event.getY(it).toDouble())}
        manager.touch(contacts.update(points,0.0,0.0,contentWidth.toDouble(),contentHeight.toDouble()));return true
    }
    override fun surfaceCreated(holder:SurfaceHolder){manager.setSurface(holder.surface)}
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){manager.setSurface(holder.surface)}
    override fun surfaceDestroyed(holder:SurfaceHolder){manager.touch(contacts.release());manager.setSurface(null)}
    @Deprecated("Platform callback retained for API 16")
    @SuppressLint("GestureBackNavigation") // API33+ gesture callback registered separately; older radios use Back.
    override fun onBackPressed(){manager.stop();super.onBackPressed()}
    @android.annotation.TargetApi(33)
    private object Api33 {
        fun register(activity:Activity,back:()->Unit) {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,back)
        }
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+0.5f).toInt()
}
