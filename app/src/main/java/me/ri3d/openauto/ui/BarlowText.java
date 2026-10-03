package me.ri3d.openauto.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.TextView;

import me.ri3d.openauto.R;

/** TextView with the app's font picked from XML ({@code app:font}) and an optional wordmark style. */
public class BarlowText extends TextView {
    public BarlowText(Context context) {
        this(context, null);
    }

    public BarlowText(Context context, AttributeSet attrs) {
        super(context, attrs);
        int font = Fonts.REGULAR;
        boolean wordmark = false;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.BarlowText);
            font = a.getInt(R.styleable.BarlowText_font, Fonts.REGULAR);
            wordmark = a.getBoolean(R.styleable.BarlowText_wordmark, false);
            a.recycle();
        }
        setFont(font);
        if (wordmark) {
            setAllCaps(true);
            if (Build.VERSION.SDK_INT >= 21) setLetterSpacing(0.06f); // reference: letter-spacing 0.06em
        }
    }

    public void setFont(int which) {
        if (!isInEditMode()) setTypeface(Fonts.get(getContext(), which));
    }
}
