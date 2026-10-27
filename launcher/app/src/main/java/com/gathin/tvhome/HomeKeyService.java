package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();

    private boolean isBlackoutActive = false;
    private View blackoutView = null;
    private WindowManager windowManager = null;
    private final Handler blackoutHandler = new Handler(Looper.getMainLooper());
    private boolean isHoldingPause = false;
    private boolean longPressTriggered = false;
    private boolean isDispatchingShortPress = false;
    private int wakingKeyCode = KeyEvent.KEYCODE_UNKNOWN;

    private final Runnable blackoutRunnable = () -> {
        if (isHoldingPause) {
            longPressTriggered = true;
            showBlackout();
        }
    };

    private void showBlackout() {
        if (isBlackoutActive) return;
        blackoutHandler.post(() -> {
            if (isBlackoutActive) return;
            try {
                if (windowManager == null) {
                    windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                }
                if (blackoutView == null) {
                    blackoutView = new View(this);
                    blackoutView.setBackgroundColor(0xFF000000);
                    blackoutView.setFocusable(false);
                    blackoutView.setClickable(true);
                    blackoutView.setOnClickListener(v -> dismissBlackout());
                }
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.OPAQUE
                );
                windowManager.addView(blackoutView, lp);
                isBlackoutActive = true;
            } catch (Exception e) {
                Log.e("HomeKeyService", "Error showing blackout overlay", e);
            }
        });
    }

    private void dismissBlackout() {
        if (!isBlackoutActive) return;
        blackoutHandler.post(() -> {
            if (!isBlackoutActive) return;
            try {
                if (windowManager != null && blackoutView != null) {
                    windowManager.removeViewImmediate(blackoutView);
                }
            } catch (Exception e) {
                Log.e("HomeKeyService", "Error removing blackout overlay", e);
            } finally {
                isBlackoutActive = false;
            }
        });
    }

    private boolean isPauseKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @Override
    public void onDestroy() {
        dismissBlackout();
        blackoutHandler.removeCallbacksAndMessages(null);
        stopWaitingForUnlock();
        super.onDestroy();
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        stockHome.clear();
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isDispatchingShortPress) return false;

        int kc = event.getKeyCode();

        // 1. If blackout is active: volume keys pass through; consuming pause key release; any other key wakes up
        if (isBlackoutActive) {
            if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN || kc == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return false;
            }
            // Consume the release of the pause key that started the blackout
            if (isHoldingPause && isPauseKey(kc)) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    isHoldingPause = false;
                    longPressTriggered = false;
                }
                return true;
            }
            // Any other key (or subsequent press) wakes up the screen
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                wakingKeyCode = kc;
                dismissBlackout();
            }
            return true;
        }

        // 2. Consume the ACTION_UP of the key that just woke up the screen so it does not trigger app actions
        if (wakingKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (kc == wakingKeyCode) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    wakingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                }
                return true;
            } else {
                wakingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            }
        }

        // 3. Long press detection for Pause button -> Turn off OLED screen
        if (isPauseKey(kc)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    isHoldingPause = true;
                    longPressTriggered = false;
                    blackoutHandler.postDelayed(blackoutRunnable, 750);
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                isHoldingPause = false;
                blackoutHandler.removeCallbacks(blackoutRunnable);
                if (longPressTriggered) {
                    longPressTriggered = false;
                    return true;
                }
                // Short press: dispatch standard Play/Pause to the active media player
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    isDispatchingShortPress = true;
                    try {
                        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, kc));
                        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, kc));
                    } finally {
                        isDispatchingShortPress = false;
                    }
                }
                return true;
            }
        }
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        if (stockHome.isEmpty() || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = event.getClassName();
        if (cls != null && stockHome.contains(cls.toString())) {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        }
    }

    @Override
    public void onInterrupt() {
    }
}
