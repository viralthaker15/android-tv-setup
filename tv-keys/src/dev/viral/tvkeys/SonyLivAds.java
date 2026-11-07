package dev.viral.tvkeys;

import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells AdMuteService when an ad break in a SonyLIV live match starts and ends.
 *
 * SonyLIV's live streams go through AWS Elemental MediaTailor, which stitches the ads into the stream on the server:
 * the DNS filter can't remove them, and SonyLIV's media session looks the same during an ad as during play (playing,
 * speed 1, seekable). Two log lines give the breaks away. Measured 2026-11-07 on the India vs NZ one-day match: five
 * 40 s breaks and a pre-roll, each start and end timed by the owner pressing a remote button.
 *
 *  1. SonyLIV logs each HTTP request (OkHttp "callStart: Request{method=GET, url=...}"). Ad video comes from
 *     MediaTailor's ad path "/v1/segment/...", the match from ".ts" files on SonyLIV's CDN. The switch shows up
 *     2 to 6.4 s before the screen changes (the player loads ahead), so it says which way the next change goes.
 *  2. The audio decoder is reconfigured when playback crosses from match to ad or back (Codec2 "CCodecConfig: c2
 *     config diff is ..."). It came 1.1 to 1.9 s before each of the owner's presses: that is the moment.
 *
 * Each switch in (1) is queued; each decoder change in (2) takes the oldest queued switch and applies it. A decoder
 * change with nothing queued (pause and resume, a commentary-language change) is ignored. If an app update stops
 * SonyLIV logging these lines, nothing is queued and nothing is muted.
 *
 * The log reader runs only while SonyLIV has a media session, filtered to SonyLIV's process inside logd. On start it
 * replays the last few seconds, because a pre-roll starts about a second before SonyLIV publishes its session.
 * All decisions run on the main thread; the reader thread only parses lines.
 */
final class SonyLivAds {
    interface Listener {
        void onSonyLivAd(boolean inAd, String why);
    }

    static final String PACKAGE = "com.sonyliv";
    private static final String TAG = "TVKeys.SonyLIV";

    /** One line of `logcat -v time -v epoch`: "  1794026811.974 D/CCodecConfig( 6158): c2 config diff is ...". */
    private static final Pattern LINE =
            Pattern.compile("^\\s*(\\d+)\\.(\\d{3})\\s+[A-Z]/([^(]+?)\\(\\s*(\\d+)\\):\\s?(.*)$");
    private static final Pattern GET = Pattern.compile("method=GET, url=https://([^/,\\s]+)(/[^?,\\s]*)");
    private static final Pattern MATCH_VIDEO = Pattern.compile("\\.(ts|m4s|mp4|m4v|m4a|aac)$");
    private static final String DECODER = "c2 config diff is";

    private static final long ONE_DECODER_CHANGE_MS = 500;   // its 2 or 3 lines arrive within ~35 ms
    private static final long STALE_MS = 20_000;             // a switch never waited more than 6.4 s for the screen
    private static final long MISSED_END_MS = 20_000;        // back on the match this long, still "in ad": give up
    private static final long REPLAY_MS = 15_000;
    private static final long APPLY_DELAY_MS = 300;          // a replayed burst ends in one decision, not a flicker

    private final Handler main;
    private final Listener listener;

    private final ArrayDeque<Boolean> queuedIsAd = new ArrayDeque<>();
    private final ArrayDeque<Long> queuedAt = new ArrayDeque<>();
    private Boolean lastFetchIsAd;   // null: nothing fetched yet since following started, or since the stream began
    private boolean streamStartSeen; // the stream began while followed, so its first decoder start can be matched
    private long lastDecoderMs;
    private boolean inAd;            // what is on screen, as far as the log says
    private boolean reported;        // what the listener was last told
    private long stoppedAtMs;
    private Reader reader;

    private final Runnable apply = () -> report(inAd ? "SonyLIV ad break on screen" : "SonyLIV match back on screen");
    private final Runnable missedEnd = () -> {
        if (inAd && Boolean.FALSE.equals(lastFetchIsAd)) {
            Log.w(TAG, "back on the match for 20 s but no decoder change seen; ending the ad");
            inAd = false;
            report("SonyLIV ad end missed");
        }
    };

    SonyLivAds(Handler main, Listener listener) {
        this.main = main;
        this.listener = listener;
    }

    /** SonyLIV has a media session: follow its log (no-op if already following). */
    void start() {
        if (reader != null) return;
        long since = Math.max(System.currentTimeMillis() - REPLAY_MS, stoppedAtMs);
        newStream();
        reader = new Reader(since);
        reader.start();
    }

    /** SonyLIV's session is gone, or the service is going away. */
    void stop(String why) {
        if (reader == null) return;
        reader.shutDown();
        reader = null;
        stoppedAtMs = System.currentTimeMillis();
        newStream();
        main.removeCallbacks(apply);
        main.removeCallbacks(missedEnd);
        inAd = false;
        report(why);
    }

    void onPlaybackState(int state) {
        if (state != PlaybackState.STATE_PAUSED && state != PlaybackState.STATE_STOPPED
                && state != PlaybackState.STATE_ERROR) {
            return;
        }
        // Resuming reconfigures the decoder; nothing must be queued then, or it would take the next switch early.
        clearQueue();
        if (inAd) {
            inAd = false;
            main.removeCallbacks(apply);
            report("SonyLIV paused or stopped");
        }
    }

    private void newStream() {
        clearQueue();
        lastFetchIsAd = null;
        streamStartSeen = false;
    }

    private void clearQueue() {
        queuedIsAd.clear();
        queuedAt.clear();
    }

    private void onFetch(long t, String host, String path) {
        if (reader == null) return;
        if (path.startsWith("/v1/master/")) {   // a new stream: opening the match, or a language change
            Log.d(TAG, "new stream");
            newStream();
            streamStartSeen = true;
            return;
        }
        boolean isAd;
        if (path.startsWith("/v1/segment/") || path.startsWith("/tm/")) {
            isAd = true;
        } else if (host.endsWith("slivcdn.com") && MATCH_VIDEO.matcher(path).find()) {
            isAd = false;
        } else {
            return;   // playlists, the app's API, images
        }
        if (lastFetchIsAd != null && lastFetchIsAd == isAd) return;
        if (lastFetchIsAd == null && !streamStartSeen) {
            // Joined mid-stream (TV Keys restarted while SonyLIV played): where the screen is relative to this
            // fetch is unknown, so queue nothing and wait for the next switch.
            lastFetchIsAd = isAd;
            Log.i(TAG, "joined mid-stream, now loading " + (isAd ? "an ad" : "the match") + "; waiting for a switch");
            return;
        }
        lastFetchIsAd = isAd;
        queuedIsAd.add(isAd);
        queuedAt.add(t);
        Log.i(TAG, "loading " + (isAd ? "an ad" : "the match") + "; the screen follows at the next decoder change");
        main.removeCallbacks(missedEnd);
        if (!isAd) main.postDelayed(missedEnd, MISSED_END_MS);
    }

    private void onDecoderChange(long t) {
        if (reader == null) return;
        if (t - lastDecoderMs < ONE_DECODER_CHANGE_MS) return;
        lastDecoderMs = t;
        while (!queuedAt.isEmpty() && t - queuedAt.peekFirst() > STALE_MS) {
            queuedAt.pollFirst();
            queuedIsAd.pollFirst();
        }
        if (queuedIsAd.isEmpty()) {
            Log.d(TAG, "decoder changed with nothing queued (pause, resume, language): ignored");
            return;
        }
        queuedAt.pollFirst();
        inAd = queuedIsAd.pollFirst();
        Log.i(TAG, (inAd ? "ad" : "match") + " on screen at " + clock(t));
        main.removeCallbacks(apply);
        main.postDelayed(apply, APPLY_DELAY_MS);
    }

    private void report(String why) {
        if (inAd == reported) return;
        reported = inAd;
        listener.onSonyLivAd(inAd, why);
    }

    private static String clock(long ms) {
        return String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000);
    }

    /** Follows SonyLIV's log until shut down; restarts logcat if it ever exits. */
    private final class Reader extends Thread {
        private volatile boolean running = true;
        private volatile Process process;
        private long since;

        Reader(long since) {
            super("tvkeys-sonyliv");
            setDaemon(true);
            this.since = since;
        }

        void shutDown() {
            running = false;
            Process p = process;
            if (p != null) p.destroy();   // unblocks readLine()
        }

        @Override
        public void run() {
            long backoffMs = 1_000;
            int pid = 0;   // SonyLIV's process: learnt from its first SonyMediaSession_ line (only SonyLIV writes it)
            while (running) {
                long startedAt = SystemClock.elapsedRealtime();
                List<String> cmd = new ArrayList<>(Arrays.asList("/system/bin/logcat", "-b", "main",
                        "-v", "time", "-v", "epoch", "-T", clock(since)));
                if (pid == 0) {
                    cmd.addAll(Arrays.asList("-s", "SonyMediaSession_:V"));
                } else {
                    // Tags only, no `-e` regex: measured during a live match, logcat with `-e` used 4.2% of one core,
                    // with tags only 0.15%, passing ~6 lines/s on to this process.
                    cmd.addAll(Arrays.asList("--pid=" + pid, "-s", "OkHttp:I", "CCodecConfig:D"));
                }
                boolean foundPid = false;
                try {
                    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    process = p;
                    if (!running) p.destroy();
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = in.readLine()) != null) {   // blocks until logd hands over a line
                            Matcher m = LINE.matcher(line);
                            if (!m.matches()) continue;
                            long t = Long.parseLong(m.group(1)) * 1000 + Integer.parseInt(m.group(2));
                            if (pid == 0) {
                                pid = Integer.parseInt(m.group(4));
                                Log.i(TAG, "following SonyLIV's log (pid " + pid + ")");
                                foundPid = true;
                                break;   // restart filtered by pid, replaying from the same point
                            }
                            since = t;   // where to pick up if logcat dies (-T is inclusive; repeats are harmless)
                            String tag = m.group(3).trim();
                            String message = m.group(5);
                            if (tag.equals("CCodecConfig") && message.startsWith(DECODER)) {
                                main.post(() -> onDecoderChange(t));
                            } else if (tag.equals("OkHttp")) {
                                Matcher g = GET.matcher(message);
                                if (g.find()) {
                                    String host = g.group(1);
                                    String path = g.group(2);
                                    main.post(() -> onFetch(t, host, path));
                                }
                            }
                        }
                    } finally {
                        p.destroy();
                    }
                } catch (IOException | RuntimeException e) {
                    if (running) Log.w(TAG, "SonyLIV log reader failed", e);
                }
                if (!running) break;
                if (foundPid) continue;
                backoffMs = SystemClock.elapsedRealtime() - startedAt > 60_000 ? 1_000 : Math.min(backoffMs * 2, 60_000);
                Log.w(TAG, "SonyLIV log ended; restarting in " + backoffMs + " ms");
                SystemClock.sleep(backoffMs);
            }
        }
    }
}
