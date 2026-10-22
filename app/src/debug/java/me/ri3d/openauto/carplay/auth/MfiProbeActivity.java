package me.ri3d.openauto.carplay.auth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.Executors;

import me.ri3d.openauto.ui.Insets;

/**
 * The "Synthetic MFi probe" launcher entry of debug builds: runs {@link SyntheticMfiProbe} and
 * shows its report. Every file operation goes through the one {@link ProbeRunner} of the process,
 * so instances never use the fixture directory together; each instance owns its pending
 * operation and cancels it in {@link #onDestroy}, after which its result is dropped. It reads
 * nothing from the intent, so no key can be imported and no path chosen from outside.
 */
@SuppressLint("SetTextI18n") // debug-only diagnostic screen, English only
public final class MfiProbeActivity extends Activity {
    private static ProbeRunner runner;

    private final String instance = "activity@" + Integer.toHexString(System.identityHashCode(this));
    private LinearLayout buttons;
    private TextView report;
    private ProbeRunner.Ticket current;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        buttons = new LinearLayout(this);
        addButton("Run again", v -> run());
        addButton("Restart process", v -> restartProcess());
        addButton("Delete fixtures", v -> delete());
        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);
        root.addView(buttons);
        root.addView(scroll);
        setContentView(root);
        Insets.fit(root);
        run();
    }

    @Override
    protected void onDestroy() {
        if (current != null) {
            runner().cancel(current);
            current = null;
        }
        super.onDestroy();
    }

    private void run() {
        final File files = getFilesDir(), apk = new File(getApplicationInfo().sourceDir);
        submit("run", "Running the synthetic probe...", cancel -> SyntheticMfiProbe.run(files, apk, cancel));
    }

    private void delete() {
        final File files = getFilesDir();
        submit("delete", "Deleting the fixtures...", cancel -> SyntheticMfiProbe.deleteFixtures(files)
                ? "Fixture directory deleted." : "The fixture directory could not be deleted.");
    }

    /**
     * Ends the app's process (an Android Auto session included) and reopens the probe, which then
     * loads the identities the last run left on disk. Queued like the other operations, so no
     * instance is halfway through the fixtures when the process goes. Android 10 and newer may block
     * the reopening; start the probe from the launcher then.
     */
    private void restartProcess() {
        final PendingIntent again = PendingIntent.getActivity(this, 0,
                new Intent(this, MfiProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        final AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
        submit("restart", "Restarting the process...", cancel -> {
            if (cancel.cancelled()) return "not restarted";
            alarms.set(AlarmManager.RTC, System.currentTimeMillis() + 1500, again);
            Process.killProcess(Process.myPid());
            return "still running";
        });
    }

    private void submit(String name, String running, ProbeRunner.Operation op) {
        setBusy(true);
        report.setText(runner().pending() > 0 ? "Waiting for another probe operation to finish..." : running);
        current = runner().submit(name + " for " + instance, op, text -> {
            current = null;
            report.setText(text);
            setBusy(false);
        });
    }

    private void addButton(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(onClick);
        buttons.addView(b);
    }

    private void setBusy(boolean busy) {
        for (int i = 0; i < buttons.getChildCount(); i++) buttons.getChildAt(i).setEnabled(!busy);
    }

    /** One per process, created on the main thread. */
    private static ProbeRunner runner() {
        if (runner == null) {
            Handler main = new Handler(Looper.getMainLooper());
            runner = new ProbeRunner(Executors.newSingleThreadExecutor(r -> new Thread(r, "mfi-probe")), main::post,
                    line -> Log.i("MfiProbeRunner", line));
        }
        return runner;
    }
}
