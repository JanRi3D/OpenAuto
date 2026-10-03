package me.ri3d.openauto.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import me.ri3d.openauto.R;

/** Holo-dark AlertDialogs (available since API 11); keeps dialog code out of the activities. */
public final class Dialogs {
    private Dialogs() {}

    public static AlertDialog.Builder builder(Context ctx) {
        return new AlertDialog.Builder(ctx, AlertDialog.THEME_HOLO_DARK);
    }

    public static void info(Context ctx, CharSequence title, CharSequence message) {
        builder(ctx).setTitle(title).setMessage(message)
                .setPositiveButton(R.string.ok, null).show();
    }

    public static void info(Context ctx, CharSequence title, CharSequence message, int actionLabel,
                            DialogInterface.OnClickListener action) {
        builder(ctx).setTitle(title).setMessage(message)
                .setNegativeButton(R.string.ok, null)
                .setPositiveButton(actionLabel, action).show();
    }
}
