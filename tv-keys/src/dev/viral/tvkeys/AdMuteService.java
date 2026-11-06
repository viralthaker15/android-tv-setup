package dev.viral.tvkeys;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mutes the TV while Prime Video plays an ad, and unmutes when the ad ends. Mute only: it never skips, seeks or
 * blocks anything.
 *
 * The signal is Prime's own media session (measured 2026-11-06, a pre-roll and a mid-roll on two titles): during an
 * ad break Prime reports STATE_PLAYING with playback speed 0 and takes away the seek actions (SEEK_TO, FAST_FORWARD,
 * REWIND, SKIP_TO_NEXT), because an ad cannot be skipped; seeking comes back the moment the ad ends. Film playback
 * always had SEEK_TO; once it showed speed 0 while resuming, so both conditions are required. A live stream cannot
 * be sought either, but plays at speed 1, so it is not muted.
 *
 * Why a notification listener: it is the only way an app without system privileges may watch other apps' media
 * sessions (MediaSessionManager.getActiveSessions). It is enabled over ADB (cmd notification allow_listener). It does
 * nothing with notifications. Everything here is callbacks: no polling, no timers except the 5-minute safety net
 * that exists only while the TV is muted.
 *
 * Never leaves the TV muted: the "we muted it" flag is saved before muting, and is undone when the ad ends, when
 * Prime pauses, stops or goes away, when this service is unbound or restarted (a crash), and after 5 minutes at most.
 * If you change the volume or mute yourself during an ad, it leaves the sound as you set it.
 */
public final class AdMuteService extends NotificationListenerService {
    private static final String TAG = "TVKeys.AdMute";

    /** Apps whose ad signal was verified. SonyLIV and JioHotstar were not tested, so they are not here. */
    private static final Set<String> APPS = Set.of("com.amazon.amazonvideo.livingroom");

    private static final long SEEK_ACTIONS = PlaybackState.ACTION_SEEK_TO;
    private static final long MAX_MUTE_MS = 5 * 60_000L;
    private static final String PREFS = "ad_mute";
    private static final String MUTED_BY_US = "muted_by_us";
    // AudioManager.STREAM_MUTE_CHANGED_ACTION and its extras are hidden constants; the broadcast itself is public.
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, MediaController> controllers = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();
    private MediaSessionManager sessions;
    private AudioManager audio;
    private SharedPreferences prefs;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;

    /** The session that is in an ad break right now, or null. */
    private MediaSession.Token inAd;
    private boolean watchingMute;

    private final Runnable safetyNet = () -> unmuteIfOurs("still muted after 5 minutes");

    private final BroadcastReceiver muteChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
            if (!intent.getBooleanExtra(EXTRA_MUTED, true) && mutedByUs()) {
                // You unmuted (or turned the volume up) during the ad: the sound is yours again until the next ad.
                Log.i(TAG, "unmuted by you during the ad; leaving it");
                setMutedByUs(false);
                stopWatchingMute();
            }
        }
    };

    @Override
    public void onListenerConnected() {
        audio = getSystemService(AudioManager.class);
        sessions = getSystemService(MediaSessionManager.class);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // A previous instance was stopped or crashed while it had the TV muted.
        unmuteIfOurs("restarted");
        ComponentName me = new ComponentName(this, AdMuteService.class);
        sessionsListener = this::onSessionsChanged;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            onSessionsChanged(sessions.getActiveSessions(me));
            Log.i(TAG, "watching media sessions of " + APPS);
        } catch (SecurityException e) {
            Log.w(TAG, "no access to media sessions (notification access not granted?)", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        shutDown("listener disconnected");
    }

    @Override
    public void onDestroy() {
        shutDown("service destroyed");
        super.onDestroy();
    }

    private void shutDown(String why) {
        if (sessions != null && sessionsListener != null) {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener);
            sessionsListener = null;
        }
        for (Map.Entry<MediaSession.Token, MediaController> e : controllers.entrySet()) {
            e.getValue().unregisterCallback(callbacks.get(e.getKey()));
        }
        controllers.clear();
        callbacks.clear();
        inAd = null;
        handler.removeCallbacks(safetyNet);
        if (prefs != null) unmuteIfOurs(why);
    }

    private void onSessionsChanged(List<MediaController> active) {
        Set<MediaSession.Token> present = new HashSet<>();
        if (active != null) {
            for (MediaController c : active) {
                if (!APPS.contains(c.getPackageName())) continue;
                MediaSession.Token token = c.getSessionToken();
                present.add(token);
                if (controllers.containsKey(token)) continue;
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        evaluate(c, state);
                    }

                    @Override
                    public void onSessionDestroyed() {
                        forget(token, "session destroyed");
                    }
                };
                c.registerCallback(cb, handler);
                controllers.put(token, c);
                callbacks.put(token, cb);
                evaluate(c, c.getPlaybackState());
            }
        }
        for (MediaSession.Token token : new HashSet<>(controllers.keySet())) {
            if (!present.contains(token)) forget(token, "session no longer active");
        }
    }

    private void forget(MediaSession.Token token, String why) {
        MediaController c = controllers.remove(token);
        MediaController.Callback cb = callbacks.remove(token);
        if (c != null && cb != null) c.unregisterCallback(cb);
        if (token.equals(inAd)) adEnded(why);
    }

    private void evaluate(MediaController c, PlaybackState s) {
        MediaSession.Token token = c.getSessionToken();
        int state = s == null ? PlaybackState.STATE_NONE : s.getState();
        boolean cannotSeek = s != null && (s.getActions() & SEEK_ACTIONS) == 0;
        boolean adStarts = state == PlaybackState.STATE_PLAYING && cannotSeek && s.getPlaybackSpeed() == 0f;
        // Once in an ad, a re-buffer inside it (seek still off) does not count as the end.
        boolean adGoesOn = cannotSeek
                && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING);
        // A few lines per film: the evidence trail for every mute decision.
        MediaMetadata m = c.getMetadata();
        Log.d(TAG, c.getPackageName() + " state=" + state + " speed=" + (s == null ? 0 : s.getPlaybackSpeed())
                + " actions=" + (s == null ? 0 : s.getActions())
                + " metadata=" + (m == null ? "null" : new TreeSet<>(m.keySet())));
        if (inAd == null && adStarts) {
            inAd = token;
            Log.i(TAG, c.getPackageName() + ": ad started");
            mute();
        } else if (token.equals(inAd) && !adGoesOn) {
            adEnded(c.getPackageName() + ": ad over (state " + state + ")");
        }
    }

    private void adEnded(String why) {
        inAd = null;
        unmuteIfOurs(why);
    }

    private void mute() {
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            Log.i(TAG, "already muted by you; not touching it");
            return;
        }
        setMutedByUs(true);   // saved first, so a crash right after never leaves it muted for good
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
        handler.postDelayed(safetyNet, MAX_MUTE_MS);
        if (!watchingMute) {
            registerReceiver(muteChanged, new IntentFilter(MUTE_CHANGED));
            watchingMute = true;
        }
        Log.i(TAG, "muted");
    }

    private void unmuteIfOurs(String why) {
        handler.removeCallbacks(safetyNet);
        stopWatchingMute();
        if (!mutedByUs()) return;
        setMutedByUs(false);
        if (audio != null && audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
        }
        Log.i(TAG, "unmuted: " + why);
    }

    private void stopWatchingMute() {
        if (watchingMute) {
            unregisterReceiver(muteChanged);
            watchingMute = false;
        }
    }

    private boolean mutedByUs() {
        return prefs.getBoolean(MUTED_BY_US, false);
    }

    private void setMutedByUs(boolean value) {
        prefs.edit().putBoolean(MUTED_BY_US, value).commit();   // commit, not apply: it must be on disk now
    }
}
