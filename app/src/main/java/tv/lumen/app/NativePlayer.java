package tv.lumen.app;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The full-screen video player for the Fire TV / Android app.
 * It uses Android's own media player (Media3 / ExoPlayer), which can play
 * Dolby Digital audio and more formats than the web player.
 * The Lumen interface is drawn on top of it by the (transparent) WebView.
 * All methods must be called on the main thread.
 */
public class NativePlayer {
    /** Sends events to the web app. */
    public interface Emitter {
        void emit(String type, String json);
    }

    private final Context context;
    private final PlayerView view;
    private final Emitter out;
    private final String defaultUserAgent;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private String bufferMode = "";
    private final List<String> urls = new ArrayList<>();
    private int urlIndex;
    private boolean live;
    private String userAgent = "";
    private long startMs;
    private int reconnects;
    private boolean playedOnce;
    private long droppedFrames;
    private final List<int[]> audioRefs = new ArrayList<>();
    private final List<int[]> textRefs = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            sendState();
            if (player != null && !urls.isEmpty()) main.postDelayed(this, 1000);
        }
    };

    public NativePlayer(Context context, PlayerView view, String defaultUserAgent, Emitter out) {
        this.context = context;
        this.view = view;
        this.out = out;
        this.defaultUserAgent = defaultUserAgent;
        view.setUseController(false);
        view.setFocusable(false);
        view.setShutterBackgroundColor(Color.BLACK);
        view.setKeepContentOnPlayerReset(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setVisibility(View.GONE);
    }

    /** Start playing. json = {urls:[...], live:true, ua:"", start:seconds, buffer:"normal"} */
    public void play(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray list = o.optJSONArray("urls");
            urls.clear();
            if (list != null) for (int i = 0; i < list.length(); i++) urls.add(list.getString(i));
            if (urls.isEmpty()) return;
            live = o.optBoolean("live", true);
            userAgent = o.optString("ua", "");
            startMs = (long) (o.optDouble("start", 0) * 1000);
            ensurePlayer(o.optString("buffer", "normal"));
        } catch (Exception e) {
            out.emit("error", errorJson("Bad request: " + e.getMessage()));
            return;
        }
        urlIndex = 0;
        reconnects = 0;
        playedOnce = false;
        droppedFrames = 0;
        view.setVisibility(View.VISIBLE);
        load();
        main.removeCallbacks(tick);
        main.postDelayed(tick, 500);
    }

    private void ensurePlayer(String buffer) {
        if (player != null && buffer.equals(bufferMode)) return;
        release();
        bufferMode = buffer;
        int min = 15000, max = 50000, start = 2500, rebuffer = 5000;
        if ("low".equals(buffer)) { min = 5000; max = 15000; start = 1000; rebuffer = 2000; }
        if ("large".equals(buffer)) { min = 30000; max = 120000; start = 3000; rebuffer = 8000; }
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(min, max, start, rebuffer)
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(context, renderers)
                .setLoadControl(loadControl)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.addListener(listener);
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onDroppedVideoFrames(EventTime eventTime, int droppedCount, long elapsedMs) {
                droppedFrames += droppedCount;
            }
        });
        view.setPlayer(player);
    }

    private void load() {
        String url = urls.get(urlIndex);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent.isEmpty() ? defaultUserAgent : userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES);
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(http, extractors);
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        String mime = guessMime(url);
        if (mime != null) item.setMimeType(mime);
        MediaSource source = factory.createMediaSource(item.build());
        if (startMs > 0) player.setMediaSource(source, startMs);
        else player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        out.emit("loading", "{}");
    }

    private String guessMime(String url) {
        String path = url.toLowerCase(Locale.US);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return MimeTypes.APPLICATION_M3U8;
        if (path.endsWith(".ts")) return MimeTypes.VIDEO_MP2T;
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (live && !last.contains(".")) return MimeTypes.VIDEO_MP2T;
        return null;
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_BUFFERING) out.emit("waiting", "{}");
            else if (state == Player.STATE_READY) {
                playedOnce = true;
                reconnects = 0;
                out.emit("playing", "{}");
            } else if (state == Player.STATE_ENDED) out.emit("ended", "{}");
            sendState();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            sendState();
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            sendState();
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            sendState();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (player == null) return;
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            if (playedOnce && live && reconnects < 5) {
                reconnects++;
                out.emit("reconnect", "{}");
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (player != null && !urls.isEmpty()) player.prepare();
                    }
                }, 1500);
                return;
            }
            if (urlIndex < urls.size() - 1) {
                urlIndex++;
                playedOnce = false;
                load();
                return;
            }
            out.emit("error", errorJson(error.getErrorCodeName() + (error.getMessage() != null ? ": " + error.getMessage() : "")));
        }
    };

    public void stop() {
        main.removeCallbacks(tick);
        urls.clear();
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        view.setVisibility(View.GONE);
    }

    public void release() {
        main.removeCallbacks(tick);
        if (player != null) {
            player.release();
            player = null;
        }
        view.setPlayer(null);
    }

    public void pause(boolean paused) {
        if (player != null) player.setPlayWhenReady(!paused);
    }

    public void seek(double seconds) {
        if (player != null) player.seekTo((long) Math.max(0, seconds * 1000));
    }

    public void setVolume(float volume) {
        if (player != null) player.setVolume(volume);
    }

    public void setFit(String mode) {
        int m = AspectRatioFrameLayout.RESIZE_MODE_FIT;
        if ("fill".equals(mode) || "zoom".equals(mode)) m = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        if ("stretch".equals(mode)) m = AspectRatioFrameLayout.RESIZE_MODE_FILL;
        view.setResizeMode(m);
    }

    public void setAudioTrack(int index) {
        if (player == null || index < 0 || index >= audioRefs.size()) return;
        selectTrack(audioRefs.get(index));
    }

    public void setTextTrack(int index) {
        if (player == null) return;
        if (index < 0 || index >= textRefs.size()) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            return;
        }
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        selectTrack(textRefs.get(index));
    }

    private void selectTrack(int[] ref) {
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        if (ref[0] >= groups.size()) return;
        Tracks.Group g = groups.get(ref[0]);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), ref[1]))
                .build());
    }

    private void sendState() {
        if (player == null || urls.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            long dur = player.getDuration();
            boolean isLive = player.isCurrentMediaItemLive() || live && dur == C.TIME_UNSET;
            o.put("pos", player.getCurrentPosition() / 1000.0);
            o.put("dur", isLive || dur == C.TIME_UNSET ? 0 : dur / 1000.0);
            o.put("playing", player.isPlaying());
            int s = player.getPlaybackState();
            o.put("state", s == Player.STATE_READY ? "ready" : s == Player.STATE_BUFFERING ? "buffering" : s == Player.STATE_ENDED ? "ended" : "idle");
            o.put("buf", Math.max(0, player.getBufferedPosition() - player.getCurrentPosition()) / 1000.0);
            VideoSize vs = player.getVideoSize();
            o.put("w", vs.width);
            o.put("h", vs.height);
            o.put("dropped", droppedFrames);
            Format vf = player.getVideoFormat();
            if (vf != null) {
                o.put("vcodec", codecName(vf));
                if (vf.bitrate > 0) o.put("vbr", vf.bitrate);
                if (vf.frameRate > 0) o.put("fps", Math.round(vf.frameRate));
            }
            Format af = player.getAudioFormat();
            if (af != null) o.put("acodec", audioName(af));

            audioRefs.clear();
            textRefs.clear();
            JSONArray audio = new JSONArray();
            JSONArray subs = new JSONArray();
            List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
            for (int gi = 0; gi < groups.size(); gi++) {
                Tracks.Group g = groups.get(gi);
                int type = g.getType();
                if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
                for (int ti = 0; ti < g.length; ti++) {
                    if (!g.isTrackSupported(ti)) continue;
                    Format f = g.getTrackFormat(ti);
                    JSONObject t = new JSONObject();
                    if (type == C.TRACK_TYPE_AUDIO) {
                        t.put("i", audioRefs.size());
                        t.put("name", trackLabel(f, audioRefs.size() + 1, true));
                        t.put("on", g.isTrackSelected(ti));
                        audioRefs.add(new int[]{gi, ti});
                        audio.put(t);
                    } else {
                        t.put("i", textRefs.size());
                        t.put("name", trackLabel(f, textRefs.size() + 1, false));
                        t.put("on", g.isTrackSelected(ti));
                        textRefs.add(new int[]{gi, ti});
                        subs.put(t);
                    }
                }
            }
            o.put("audio", audio);
            o.put("subs", subs);
            out.emit("state", o.toString());
        } catch (Exception ignored) {
        }
    }

    private static String trackLabel(Format f, int n, boolean audio) {
        StringBuilder sb = new StringBuilder();
        if (f.label != null && !f.label.isEmpty()) sb.append(f.label);
        else if (f.language != null && !f.language.isEmpty() && !"und".equals(f.language)) {
            String name = new Locale(f.language).getDisplayLanguage(Locale.ENGLISH);
            sb.append(name.isEmpty() ? f.language : name);
        } else sb.append(audio ? "Track " : "Subtitles ").append(n);
        if (audio) sb.append(" · ").append(audioName(f));
        return sb.toString();
    }

    private static String audioName(Format f) {
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
        String codec;
        switch (m) {
            case MimeTypes.AUDIO_AC3: codec = "Dolby Digital"; break;
            case MimeTypes.AUDIO_E_AC3: codec = "Dolby Digital Plus"; break;
            case MimeTypes.AUDIO_E_AC3_JOC: codec = "Dolby Atmos"; break;
            case MimeTypes.AUDIO_AC4: codec = "Dolby AC-4"; break;
            case MimeTypes.AUDIO_AAC: codec = "AAC"; break;
            case MimeTypes.AUDIO_MPEG: codec = "MP3"; break;
            case MimeTypes.AUDIO_MPEG_L2: codec = "MPEG audio"; break;
            case MimeTypes.AUDIO_OPUS: codec = "Opus"; break;
            case MimeTypes.AUDIO_DTS: codec = "DTS"; break;
            default: codec = m.replace("audio/", "").toUpperCase(Locale.US);
        }
        int ch = f.channelCount;
        if (ch == 6) codec += " 5.1";
        else if (ch == 8) codec += " 7.1";
        else if (ch == 2) codec += " stereo";
        else if (ch == 1) codec += " mono";
        return codec;
    }

    private static String codecName(Format f) {
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
        switch (m) {
            case MimeTypes.VIDEO_H264: return "H.264";
            case MimeTypes.VIDEO_H265: return "H.265 / HEVC";
            case MimeTypes.VIDEO_MPEG2: return "MPEG-2";
            case MimeTypes.VIDEO_VP9: return "VP9";
            case MimeTypes.VIDEO_AV1: return "AV1";
            default: return m.replace("video/", "");
        }
    }

    private static String errorJson(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg", msg);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
