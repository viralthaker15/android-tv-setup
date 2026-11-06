package dev.viral.tvkeys;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens apps from the remote's dead app buttons by reading the window manager's per-key debug line.
 *
 * Why a log reader: Hisense smartsense hands these buttons to the disabled Google TV home by explicit
 * component, so no app can receive them, and any key-filtering accessibility service stops held volume
 * from ramping on this firmware. This service filters nothing; the system binds it only so the process
 * stays alive and comes back after a reboot. The firmware logs every dispatched key as
 *
 *   D/WindowManager(  833): interceptKeyTi keyCode=293 down=true repeatCount=0 keyguardOn=false canceled=false
 *
 * and READ_LOGS (granted over ADB) lets this app see that line. The reader thread blocks in readLine() on
 * logcat's output; it never polls, so at idle it costs nothing until a log line arrives.
 */
public final class KeysService extends AccessibilityService {
    private static final String TAG = "TVKeys";

    /** One line of `logcat -v time -v epoch`: "  1793948938.160 D/WindowManager(  811): message". */
    private static final Pattern LINE =
            Pattern.compile("^\\s*(\\d+)\\.(\\d{3})\\s+[A-Z]/WindowManager\\(\\s*(\\d+)\\):\\s?(.*)$");
    /** A key-down that is not a held-key repeat. */
    private static final Pattern KEY_DOWN =
            Pattern.compile("^interceptKeyTi keyCode=(\\d+) down=true repeatCount=0\\b");

    private static final String LAUNCHER = "com.gathin.tvhome";
    private static final String APP_LIBRARY = "com.gathin.tvhome.action.APP_LIBRARY";

    private volatile boolean running;
    private volatile Process logcat;

    @Override
    protected void onServiceConnected() {
        running = true;
        Thread reader = new Thread(this::readLoop, "tvkeys-logcat");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public void onDestroy() {
        running = false;
        Process p = logcat;
        if (p != null) p.destroy();   // unblocks readLine() in the reader thread
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * Follows the log until the service goes away; restarts logcat if it ever exits.
     *
     * The main log carries ~24 lines/s at idle here (mostly AirPlay), and `-s` filters tags inside logcat, so a
     * plain reader wakes for every one of them. `--pid` is filtered inside logd instead, so once the window
     * manager's pid is known (from its first interceptKey line: only system_server writes those, while apps may
     * log under the tag WindowManager too) the reader restarts with it and sleeps through everyone else's lines.
     * Measured on this TV: reader + logd extra 0.15% of one core with the tag only, 0.05% with --pid.
     */
    private void readLoop() {
        long backoffMs = 1_000;
        long sinceMs = System.currentTimeMillis();   // -T: only lines from here on, so no old press is replayed
        int windowManagerPid = 0;
        String replayedThrough = null;   // after narrowing: the line the new reader replays up to (already handled)
        long replayedMs = 0;
        while (running) {
            long startedAt = SystemClock.elapsedRealtime();
            List<String> cmd = new ArrayList<>(Arrays.asList("/system/bin/logcat", "-b", "main",
                    "-v", "time", "-v", "epoch",
                    "-T", String.format(Locale.ROOT, "%d.%03d", sinceMs / 1000, sinceMs % 1000)));
            if (windowManagerPid > 0) cmd.add("--pid=" + windowManagerPid);
            cmd.addAll(Arrays.asList("-s", "WindowManager:D"));
            boolean narrowed = false;
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                logcat = p;
                Log.i(TAG, "reading key log" + (windowManagerPid > 0 ? " of pid " + windowManagerPid : ""));
                try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {   // blocks until logd hands over a line
                        Matcher m = LINE.matcher(line);
                        if (!m.matches()) continue;
                        long ms = Long.parseLong(m.group(1)) * 1000 + Integer.parseInt(m.group(2));
                        if (replayedThrough != null) {
                            // -T is inclusive: skip what the previous reader already handled, up to its last line.
                            if (ms < replayedMs) continue;
                            boolean same = line.equals(replayedThrough);
                            if (ms == replayedMs && !same) continue;
                            replayedThrough = null;
                            if (same) continue;
                        }
                        String message = m.group(4);
                        Matcher key = KEY_DOWN.matcher(message);
                        if (key.find()) onKeyDown(Integer.parseInt(key.group(1)));
                        if (windowManagerPid == 0 && message.startsWith("interceptKey")) {
                            windowManagerPid = Integer.parseInt(m.group(3));
                            sinceMs = ms;
                            replayedMs = ms;
                            replayedThrough = line;
                            narrowed = true;
                            break;
                        }
                    }
                } finally {
                    p.destroy();
                }
            } catch (IOException | RuntimeException e) {
                // Expected when the service is unbound (onDestroy closes logcat), e.g. while UI Automator runs.
                if (running) Log.w(TAG, "key log reader failed", e);
            }
            if (!running) break;
            if (narrowed) continue;   // straight back in, now filtered by logd
            // logcat ended (logd restarted, or READ_LOGS missing). Wait, longer each time it dies quickly.
            backoffMs = SystemClock.elapsedRealtime() - startedAt > 60_000 ? 1_000 : Math.min(backoffMs * 2, 60_000);
            Log.w(TAG, "key log ended; restarting in " + backoffMs + " ms");
            SystemClock.sleep(backoffMs);
            sinceMs = System.currentTimeMillis();
            replayedThrough = null;
        }
    }

    /**
     * The button map for remote CT-95042, each code read from interceptKeyTi while the button was pressed on the
     * real remote (2026-11-06). Scan codes for reference: gear 598, YouTube 469, Prime 468, Play 466, Apps 188.
     */
    private void onKeyDown(int keyCode) {
        switch (keyCode) {
            case 83:    // gear (KEYCODE_NOTIFICATION)
                start("gear", new Intent(Settings.ACTION_SETTINGS).setPackage("com.android.tv.settings"));
                break;
            case 293:   // YouTube
                openApp("YouTube", "com.google.android.youtube.tv");
                break;
            case 295:   // Prime Video
                openApp("Prime Video", "com.amazon.amazonvideo.livingroom");
                break;
            case 303:   // Google Play
                openApp("Google Play", "com.android.vending");
                break;
            case 284:   // Apps (KEYCODE_ALL_APPS): the CleanYourTV launcher's App library
                start("Apps", new Intent(APP_LIBRARY).setClassName(LAUNCHER, LAUNCHER + ".MainActivity"));
                break;
            default:
                break;
        }
    }

    /** An app's TV entry if it has one, else its phone entry, as the launcher opens it. */
    private void openApp(String button, String pkg) {
        PackageManager pm = getPackageManager();
        Intent open = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (open == null) open = pm.getLaunchIntentForPackage(pkg);
        if (open == null) {
            Log.w(TAG, button + ": " + pkg + " not installed or disabled");
            return;
        }
        start(button, open);
    }

    private void start(String button, Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Log.i(TAG, button + " -> " + intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, button + ": could not open " + intent, e);
        }
    }
}
