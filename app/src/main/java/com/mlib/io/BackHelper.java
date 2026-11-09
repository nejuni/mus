package com.mlib.io;

import android.app.Activity;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Predictive-back support (Android 13+; required on targetSdk 36 where onBackPressed() is no longer called). */
final class BackHelper {
    private OnBackInvokedCallback cb;

    void set(final Activity a, boolean enabled, final Runnable action) {
        OnBackInvokedDispatcher disp = a.getOnBackInvokedDispatcher();
        if (enabled && cb == null) {
            cb = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    action.run();
                }
            };
            disp.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
        } else if (!enabled && cb != null) {
            disp.unregisterOnBackInvokedCallback(cb);
            cb = null;
        }
    }
}
