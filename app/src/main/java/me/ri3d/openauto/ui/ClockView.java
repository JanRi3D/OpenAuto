package me.ri3d.openauto.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.widget.TextView;

import java.util.Date;

/**
 * Header clock. Updated by ACTION_TIME_TICK (once a minute, sent by the system) instead of a
 * timer, and only while attached to a window. TextClock needs API 17; this is API 1.
 */
public class ClockView extends TextView {
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    public ClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (!isInEditMode()) setTypeface(Fonts.get(context, Fonts.MEDIUM));
        refresh();
    }

    private void refresh() {
        setText(DateFormat.getTimeFormat(getContext()).format(new Date()));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(tick, f);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            getContext().unregisterReceiver(tick);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDetachedFromWindow();
    }
}
