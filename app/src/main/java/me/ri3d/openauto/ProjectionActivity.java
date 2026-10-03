package me.ri3d.openauto;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import me.ri3d.openauto.aa.Session;
import me.ri3d.openauto.aa.VideoGeometry;
import me.ri3d.openauto.aa.Wire;
import me.ri3d.openauto.media.VideoDecoder;
import me.ri3d.openauto.ui.Insets;

/**
 * Full-screen stage for the phone's video plus a small status line while connecting. The video
 * keeps its aspect ratio: the phone is told to render a content area shaped like this stage
 * (video margins), and the video view shows exactly that area. Touch is converted to content
 * coordinates. Nothing is drawn here once video flows: the phone's own Android Auto interface is
 * what the user sees.
 */
public class ProjectionActivity extends Activity implements ConnectionManager.Listener {
    private ConnectionManager cm;
    private FrameLayout stage;
    private View videoView;      // SurfaceView, or TextureView when this app crops the margins itself
    private Surface shown;       // the video view's surface while it exists
    private int decoderCrop;     // VideoDecoder.CROP_*: how the decoder removes the margins for a SurfaceView
    private boolean started;
    private TextView status;
    private final Handler ui = new Handler();
    private final Runnable statsTick = this::updateStatus;
    private final int[] point = new int[2];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cm = ConnectionManager.get(this);
        if (cm.prefs().keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_projection);
        Insets.immersive(getWindow());
        Insets.fit(findViewById(R.id.root)); // keeps video and controls clear of a camera cutout
        stage = (FrameLayout) findViewById(R.id.stage);
        status = (TextView) findViewById(R.id.status);
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) ui.post(this::onStageSized); // adds a view: not during layout
        });
        findViewById(R.id.close).setOnClickListener(v -> {
            cm.stop();
            finish();
        });
        if (android.os.Build.VERSION.SDK_INT >= 33) Api33.registerBack(this, this::onBack);
        // Android 6+: the assistant needs the microphone; ask once here rather than failing silently later.
        if (cm.prefs().mic() && !Perms.hasMic(this)) Perms.requestMic(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        cm.setUiFocused(hasFocus);
        if (hasFocus) Insets.immersive(getWindow());
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (shown != null) cm.setSurface(shown, decoderCrop); // a TextureView keeps its surface while stopped
        cm.addListener(this);
        cm.setLeaveProjectionHandler(this::finish);
        onConnectionChanged(cm);
    }

    @Override
    protected void onStop() {
        started = false;
        if (shown != null) cm.setSurface(null, VideoDecoder.CROP_NONE); // not visible: give up video focus, like a SurfaceView does
        cm.setLeaveProjectionHandler(null);
        cm.removeListener(this);
        ui.removeCallbacks(statsTick);
        super.onStop();
    }

    // ---- video placement -------------------------------------------------------------------------

    private void onStageSized() {
        if (stage.getWidth() <= 0 || stage.getHeight() <= 0) return;
        cm.setProjectionArea(stage.getWidth(), stage.getHeight());
        layoutVideo();
    }

    /**
     * Creates or resizes the video view for the current (or upcoming) video config. The view always has
     * the content area's shape and lies inside the stage. With margins only the content area may be
     * visible: either a SurfaceView shows what the decoder outputs after cropping the margins
     * (VideoDecoder), or a TextureView draws the whole frame enlarged so that the margins fall outside
     * it. Which one is Settings › Video › Video output.
     */
    private void layoutVideo() {
        int w = stage.getWidth(), h = stage.getHeight();
        if (w <= 0 || h <= 0) return;
        Session s = cm.session();
        Session.Config c = s != null ? s.config() : cm.previewConfig();
        boolean cropped = c.marginWidth != 0 || c.marginHeight != 0;
        int[] size = VideoGeometry.fit(c, w, h);
        // "Direct": a SurfaceView, the fast path on old hardware (overlay), with the margins cropped by
        // the codec's scaling mode, or through the stream itself ("Direct 2"). "Compatible": a
        // TextureView that this app crops and draws itself: always the right picture, but a real
        // Android 4.2 head unit managed only 16 fps that way. Neither Direct variant is honoured
        // everywhere (see VideoDecoder), so "Auto" takes Direct only where it matters and is likely to
        // work: old Android with a hardware decoder.
        boolean plain = !cropped && size[0] == w && size[1] == h;
        String output = cm.prefs().videoOut();
        if ("auto".equals(output)) {
            boolean oldWithHardwareDecoder = android.os.Build.VERSION.SDK_INT < 21 && !"sw".equals(cm.prefs().decoder())
                    && me.ri3d.openauto.diag.Decoders.hasHardware();
            output = oldWithHardwareDecoder ? "direct" : "gpu";
        }
        boolean texture = !plain && "gpu".equals(output) && stage.isHardwareAccelerated();
        int crop = !cropped || texture ? VideoDecoder.CROP_NONE : "stream".equals(output) ? VideoDecoder.CROP_STREAM : VideoDecoder.CROP_SCALE;
        if (crop != decoderCrop) {
            decoderCrop = crop;
            if (shown != null && started) cm.setSurface(shown, crop);
        }
        if (videoView == null || texture != (videoView instanceof TextureView)) {
            stage.removeAllViews(); // the old view reports its surface as destroyed
            videoView = texture ? textureView() : surfaceView();
            videoView.setOnTouchListener(this::onSurfaceTouch);
            stage.addView(videoView, new FrameLayout.LayoutParams(size[0], size[1], Gravity.CENTER));
            cm.addLog("video output: " + (texture ? "TextureView" : crop == VideoDecoder.CROP_STREAM ? "SurfaceView, stream crop"
                    : crop == VideoDecoder.CROP_SCALE ? "SurfaceView, scaling-mode crop" : "SurfaceView")
                    + " " + size[0] + "x" + size[1] + " in " + w + "x" + h);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        if (lp.width != size[0] || lp.height != size[1]) {
            lp.width = size[0];
            lp.height = size[1];
            videoView.setLayoutParams(lp);
        }
        if (texture) {
            // The frame is stretched over the view; enlarge it about the centre until only the content is inside.
            Matrix m = new Matrix();
            m.setScale(c.videoWidth / (float) VideoGeometry.contentWidth(c), c.videoHeight / (float) VideoGeometry.contentHeight(c),
                    size[0] / 2f, size[1] / 2f);
            ((TextureView) videoView).setTransform(m);
        }
    }

    // ---- surface -------------------------------------------------------------------------------

    private SurfaceView surfaceView() {
        SurfaceView v = new SurfaceView(this);
        v.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                show(holder.getSurface());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                hide(holder.getSurface());
            }
        });
        return v;
    }

    private TextureView textureView() {
        TextureView v = new TextureView(this);
        v.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            private Surface surface;

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                surface = new Surface(st);
                show(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                hide(surface); // stops the decoder before its output goes away
                if (surface != null) surface.release();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        return v;
    }

    private void show(Surface s) {
        shown = s;
        if (started) cm.setSurface(s, decoderCrop);
    }

    /** A replaced view reports late; only the surface in use may be withdrawn. */
    private void hide(Surface s) {
        if (shown != s) return;
        shown = null;
        cm.setSurface(null, VideoDecoder.CROP_NONE);
    }

    // ---- input ---------------------------------------------------------------------------------

    /** Events arrive relative to the SurfaceView, which shows exactly the phone's content area. */
    private boolean onSurfaceTouch(View v, MotionEvent e) {
        Session s = cm.session();
        if (s == null) return false;
        VideoGeometry.toContent(s.config(), v.getWidth(), v.getHeight(), e.getX(), e.getY(), point);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cm.touch(Wire.TOUCH_PRESS, point[0], point[1]);
                break;
            case MotionEvent.ACTION_MOVE:
                cm.touch(Wire.TOUCH_DRAG, point[0], point[1]);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cm.touch(Wire.TOUCH_RELEASE, point[0], point[1]);
                break;
            default:
                break; // TODO: single pointer only; pinch needs POINTER_DOWN/UP (5/6) support
        }
        return true;
    }

    @Override
    @android.annotation.SuppressLint("GestureBackNavigation") // head units have a hardware Back key; predictive back is API 33+
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBack();
            return true;
        }
        int code = mediaButton(keyCode);
        if (code != 0 && cm.prefs().mediaKeys()) {
            cm.button(code, true);
            cm.button(code, false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Back key or gesture: either goes to the phone, or disconnects and returns to the launcher (Settings › Input). */
    private void onBack() {
        if ("phone".equals(cm.prefs().backKey()) && cm.isConnected()) {
            cm.button(Wire.BTN_BACK, true);
            cm.button(Wire.BTN_BACK, false);
            return;
        }
        cm.stop(); // Android Auto's own Exit entry leaves this screen but keeps the session; Back ends it
        finish();
    }

    /** Android 13+ delivers the Back gesture through a callback instead of a key event. */
    @android.annotation.TargetApi(33)
    private static final class Api33 {
        static void registerBack(Activity a, final Runnable onBack) {
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, onBack::run);
        }
    }

    static int mediaButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return Wire.BTN_TOGGLE_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return Wire.BTN_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return Wire.BTN_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_NEXT: return Wire.BTN_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return Wire.BTN_PREV;
            case KeyEvent.KEYCODE_HOME: return Wire.BTN_HOME;
            case KeyEvent.KEYCODE_SEARCH: return Wire.BTN_MICROPHONE_1;
            default: return 0;
        }
    }

    // ---- state -----------------------------------------------------------------------------------

    @Override
    public void onConnectionChanged(ConnectionManager m) {
        if (m.phase() == ConnectionManager.Phase.IDLE) {
            finish();
            return;
        }
        // ERROR included: the reason stays on screen until the user closes it (a toast was too short to read).
        layoutVideo(); // the session's margins are final once it exists
        updateStatus();
    }

    private void updateStatus() {
        ui.removeCallbacks(statsTick);
        ConnectionManager.Phase p = cm.phase();
        VideoDecoder dec = cm.media() == null ? null : cm.media().video;
        boolean videoFlowing = dec != null && dec.framesRendered > 0 && System.currentTimeMillis() - dec.lastFrameAtMs < 2000;
        // Nothing of ours covers the phone's interface while it is showing; Back (or the phone's Exit entry) leaves.
        findViewById(R.id.close).setVisibility(videoFlowing ? View.GONE : View.VISIBLE);
        if (videoFlowing) {
            status.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.VISIBLE);
            String text;
            switch (p) {
                case USB_PERMISSION: text = getString(R.string.wired_permission); break;
                case USB_SWITCHING: text = getString(R.string.phase_switching); break;
                case HOTSPOT: text = getString(R.string.phase_hotspot); break;
                case WIRELESS_WAITING: text = getString(R.string.phase_wireless_waiting) + (cm.detail() == null ? "" : " (" + cm.detail() + ")"); break;
                case CONNECTING: text = getString(R.string.status_connecting) + (cm.detail() == null ? "" : " " + cm.detail()); break;
                case VERSION: text = getString(R.string.phase_version); break;
                case HANDSHAKE: text = getString(R.string.phase_handshake); break;
                case DISCOVERY: text = getString(R.string.phase_discovery); break;
                case READY: text = getString(R.string.phase_ready, cm.detail() == null ? "" : cm.detail()); break;
                case RECONNECTING: text = getString(R.string.phase_reconnecting, cm.detail() == null ? "" : cm.detail()); break;
                case ERROR: text = getString(R.string.connection_failed, cm.detail() == null ? "" : cm.detail()); break;
                case PROJECTING:
                    text = dec != null && dec.error() != null ? getString(R.string.phase_decoder_error, dec.error())
                            : getString(R.string.phase_waiting_video);
                    break;
                default: text = p.toString();
            }
            status.setText(text);
        }
        ui.postDelayed(statsTick, 500);
    }

    public static void open(Activity from) {
        from.startActivity(new Intent(from, ProjectionActivity.class));
    }
}
