package com.kayrahanaydogan.livetv;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A native player that retries media formats and authorized alternative stream URLs. */
public final class PlayerActivity extends Activity {
    private static final int WHITE = Color.rgb(245, 245, 247);
    private static final int MUTED = Color.rgb(166, 166, 177);
    private static final int RED = Color.rgb(241, 59, 72);
    private static final int BACK = Color.rgb(9, 9, 11);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Source> sources = new ArrayList<>();
    private final ArrayList<Attempt> plan = new ArrayList<>();
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView statusText;
    private TextView sourceText;
    private View controlBar;
    private View topBar;
    private boolean failurePending = false;
    private boolean closing = false;
    private boolean everPlayed = false;
    private String playedUrl = "";
    private int attemptIndex = -1;
    private Runnable watchdog;
    private int attemptSerial = 0;

    private static final class Source {
        final String url, referrer, userAgent;
        Source(String url, String referrer, String userAgent) {
            this.url = url;
            this.referrer = referrer;
            this.userAgent = userAgent;
        }
    }

    private static final class Attempt {
        final int sourceIndex;
        final String format;
        Attempt(int sourceIndex, String format) {
            this.sourceIndex = sourceIndex;
            this.format = format;
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACK);
        getWindow().setNavigationBarColor(BACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String title = "live tv";
        try {
            String raw = getIntent().getStringExtra("player_data");
            if (raw == null || raw.length() > 350_000) throw new Exception("missing channel");
            JSONObject payload = new JSONObject(raw);
            title = payload.optString("title", "live tv");
            JSONArray list = payload.getJSONArray("sources");
            for (int i = 0; i < Math.min(list.length(), 32); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String url = goodUrl(item.optString("url", ""));
                if (url == null) continue;
                sources.add(new Source(url, safeHeader(item.optString("referrer", "")), safeHeader(item.optString("user_agent", ""))));
            }
        } catch (Exception ignored) { }
        buildUi(title);
        if (sources.isEmpty()) { setStatus("no usable streams", RED); return; }
        createPlan();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (closing || failurePending) return;
                if (state == Player.STATE_READY) {
                    cancelWatchdog();
                    if (attemptIndex < 0 || attemptIndex >= plan.size()) return;
                    everPlayed = true;
                    playedUrl = sources.get(plan.get(attemptIndex).sourceIndex).url;
                    setStatus("live · stream connected", 0xff2de0a2);
                } else if (state == Player.STATE_BUFFERING) {
                    setStatus("connecting / buffering", MUTED);
                    scheduleWatchdog(everPlayed ? 27000 : 24000);
                } else if (state == Player.STATE_ENDED) {
                    onFailure("stream ended");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                onFailure("player error " + error.errorCode);
            }
        });
        nextAttempt();
    }

    private static String goodUrl(String raw) {
        try {
            if (raw == null || raw.length() > 4096) return null;
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return null;
            if (uri.getHost() == null || uri.getUserInfo() != null) return null;
            return uri.toString();
        } catch (Exception e) { return null; }
    }

    private static String safeHeader(String text) {
        if (text == null || text.length() > 500 || text.contains("\r") || text.contains("\n")) return "";
        return text.trim();
    }

    private void createPlan() {
        for (int i = 0; i < sources.size(); i++) {
            String path;
            try {path = new URI(sources.get(i).url).getPath().toLowerCase(Locale.ROOT);}
            catch (Exception e) {path = "";}
            if (path.endsWith(".m3u8") || path.endsWith(".m3u")) {
                plan.add(new Attempt(i, "hls"));
                plan.add(new Attempt(i, "auto"));
            } else if (path.endsWith(".mpd")) {
                plan.add(new Attempt(i, "dash"));
                plan.add(new Attempt(i, "auto"));
            } else if (path.matches(".*\\.(mp4|m4v|mov|mp3|aac|ogg|webm|ts|mkv)$")) {
                plan.add(new Attempt(i, "auto"));
                plan.add(new Attempt(i, "hls"));
            } else {
                plan.add(new Attempt(i, "auto"));
                plan.add(new Attempt(i, "hls"));
                plan.add(new Attempt(i, "dash"));
            }
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private Button button(String title, Runnable onClick) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setTextSize(12);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff2a1c21));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private void buildUi(String name) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACK);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(9), dp(4), dp(9), dp(4));
        Button back = button("← back", this::finish);
        top.addView(back, new LinearLayout.LayoutParams(dp(88), dp(52)));
        TextView channel = text(name.length() > 120 ? name.substring(0, 120) : name, 16, WHITE);
        channel.setSingleLine(true);
        channel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        titleLp.setMargins(dp(8), 0, 0, 0);
        top.addView(channel, titleLp);
        Button pip = button("▣ pip", this::enterPip);
        top.addView(pip, new LinearLayout.LayoutParams(dp(78), dp(52)));
        topBar = top;
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        frame.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(12), dp(7), dp(12), dp(12));
        bottom.setOrientation(LinearLayout.VERTICAL);
        sourceText = text("source 1 / " + sources.size(), 12, MUTED);
        bottom.addView(sourceText);
        statusText = text("preparing live tv", 12, WHITE);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        bottom.addView(statusText, statusLp);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button retry = button("↻ retry", () -> {attemptIndex = -1; nextAttempt();});
        Button next = button("⇄ next source", this::nextSource);
        Button external = button("↗ external", this::openExternal);
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(50), 1.3f));
        actions.addView(external, new LinearLayout.LayoutParams(0, dp(50), 1));
        bottom.addView(actions);
        controlBar = bottom;
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
    }

    private void setStatus(String message, int color) {
        if (statusText != null) {
            statusText.setText(message);
            statusText.setTextColor(color);
        }
    }

    private void scheduleWatchdog(long delay) {
        cancelWatchdog();
        int serial = attemptSerial;
        watchdog = () -> {
            if (serial == attemptSerial && player != null && player.getPlaybackState() == Player.STATE_BUFFERING)
                onFailure("stream timed out");
        };
        handler.postDelayed(watchdog, delay);
    }

    private void cancelWatchdog() {
        if (watchdog != null) handler.removeCallbacks(watchdog);
        watchdog = null;
    }

    private void nextAttempt() {
        cancelWatchdog();
        if (closing || player == null) return;
        failurePending = false;
        attemptSerial++;
        attemptIndex++;
        if (attemptIndex >= plan.size()) {
            player.stop();
            sourceText.setText("checked all " + sources.size() + " sources");
            setStatus("all available sources failed", RED);
            return;
        }
        Attempt next = plan.get(attemptIndex);
        Source src = sources.get(next.sourceIndex);
        sourceText.setText("source " + (next.sourceIndex + 1) + "/" + sources.size()
                + " · " + next.format + " · try " + (attemptIndex + 1) + "/" + plan.size());
        setStatus("connecting...", MUTED);
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(12000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true)
                    .setUserAgent(src.userAgent.isEmpty() ? "WorldLiveTV/1.0 (Android)" : src.userAgent);
            Map<String, String> headers = new HashMap<>();
            if (!src.referrer.isEmpty()) headers.put("Referer", src.referrer);
            if (!headers.isEmpty()) http.setDefaultRequestProperties(headers);
            DefaultDataSource.Factory dataSource = new DefaultDataSource.Factory(this, http);
            DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(dataSource);
            MediaItem.Builder item = new MediaItem.Builder().setUri(src.url);
            if ("hls".equals(next.format)) item.setMimeType(MimeTypes.APPLICATION_M3U8);
            else if ("dash".equals(next.format)) item.setMimeType(MimeTypes.APPLICATION_MPD);
            player.setMediaSource(factory.createMediaSource(item.build()), true);
            player.prepare();
            player.play();
            scheduleWatchdog(24000);
        } catch (Exception e) { onFailure("format unavailable"); }
    }

    private void onFailure(String error) {
        if (closing || failurePending || player == null) return;
        failurePending = true;
        cancelWatchdog();
        setStatus(error + " · trying next", RED);
        final int failedSerial = attemptSerial;
        handler.postDelayed(() -> {
            if (!closing && attemptSerial == failedSerial) nextAttempt();
        }, 350);
    }

    private void nextSource() {
        if (player == null || plan.isEmpty()) return;
        int currentSource = attemptIndex >= 0 && attemptIndex < plan.size() ? plan.get(attemptIndex).sourceIndex : -1;
        cancelWatchdog();
        failurePending = false;
        while (attemptIndex + 1 < plan.size() && plan.get(attemptIndex + 1).sourceIndex == currentSource)
            attemptIndex++;
        nextAttempt();
    }

    private void openExternal() {
        if (sources.isEmpty()) return;
        String url = (attemptIndex >= 0 && attemptIndex < plan.size())
                ? sources.get(plan.get(attemptIndex).sourceIndex).url : sources.get(0).url;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) {Toast.makeText(this, "no external player installed", Toast.LENGTH_SHORT).show();}
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build();
                enterPictureInPictureMode(params);
            } catch (Exception e) {
                Toast.makeText(this, "picture-in-picture unavailable", Toast.LENGTH_SHORT).show();
            }
        } else Toast.makeText(this, "picture-in-picture unsupported", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        if (topBar != null) topBar.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (controlBar != null) controlBar.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (playerView != null) playerView.setUseController(!inPictureInPictureMode);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && !(Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode())) player.pause();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public void finish() {
        Intent result = new Intent();
        result.putExtra("played_url", playedUrl);
        result.putExtra("ever_played", everPlayed);
        setResult(RESULT_OK, result);
        super.finish();
    }

    @Override
    protected void onDestroy() {
        closing = true;
        cancelWatchdog();
        handler.removeCallbacksAndMessages(null);
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
