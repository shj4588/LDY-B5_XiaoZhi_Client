package cn.workbuddy.ldyb5app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ldyb5 屏幕主界面 —— 平板风格双栏（横屏 1024x600）。
 * 配色沿用 R1 小智 HUD：深色顶栏 + 黄色状态大字 + 青色主体 + 灰色按钮。
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ 顶栏：小智 · ldyb5                     HH:mm │
 *   ├───────────────────────┬──────────────────────┤
 *   │ 状态卡（大字/我/小智） │  [曲库|电台] 切换    │
 *   │ 音乐卡（now playing） │  搜索框  [搜索][停止]│
 *   │ 控制行 ×2（按钮网格） │  ┌────────────────┐  │
 *   │                       │  │ 曲库 ListView  │  │
 *   │                       │  └────────────────┘  │
 *   └───────────────────────┴──────────────────────┘
 *
 * 状态经 ScreenBus 轮询；音乐经 MusicPlayer 单例。
 * 由 Ldyb5ClientService 启动完成后唤起（files/screenui_off 可关）。
 */
public class ScreenUI extends Activity {

    private static final int C_HEADER = 0xFF16222C;
    private static final int C_BODY = 0xFF2FB4D8;
    private static final int C_CARD = 0xCC16222C;
    private static final int C_BTN = 0xFF616161;
    private static final int C_BTN_ON = 0xFFE0A62A;
    private static final int C_ACCENT = 0xFFF7CF4E;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_SUB = 0xFFCDE9F2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
    // v0.3.5：日期带星期（EEE 在 Locale.CHINA 下是「周日」）
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("M月d日 EEE", Locale.CHINA);

    private TextView stateView;
    private TextView convoView;   // v0.3.0：多行对话历史（我/小智交替）
    private ScrollView convoScroll; // v0.3.3：对话记录可拖动滚动
    private TextView nowView;
    /** v0.3.26：酷我音乐状态行（曲目 + 播放/暂停） */
    private TextView kuwoView;
    /** v0.3.26：酷我状态行上次文本（变了才 setText，避免每 500ms 重建） */
    private String lastKuwo = null;
    private TextView timeView;    // v0.3.5：时间大字（移到小智栏上方）
    private TextView dateWxView;  // v0.3.5：日期 + 天气行
    private TextView statView;    // v0.3.6：温度/CPU/内存行
    private LinearLayout clockPanel;
    private LinearLayout bodyRoot; // v0.3.9：主界面底色可改
    // 设备状态采样（2s 一次）
    private long lastStatsMs = 0;
    private long lastCpuTotal = 0;
    private long lastCpuIdle = 0;
    private TextView libTitle;
    private Button playBtn;
    /** v0.3.45：底部「静音/恢复」按钮 + 上次呈现状态（-1 未知 / 0 收音 / 1 静音） */
    private Button muteBtn;
    private int muteUiState = -1;
    private ListView libView;
    private LibAdapter adapter;
    private List<String> items = new ArrayList<String>();
    private int lastMusicTick = -1;
    private String lastNow = null;
    /** v0.3.12：上一次重画列表时的当前台名（黄标跟随用） */
    private String lastMarker = null;
    // v0.3.0：对话历史（轮询 ScreenBus，值变化即追加一行）
    // v0.3.3：上限放大到 100 行，配合 ScrollView 拖动回看
    private final List<String> convo = new ArrayList<String>();
    private String lastStt = null;
    private String lastLlm = null;
    private static final int CONVO_MAX_LINES = 100;

    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            main.postDelayed(this, 500);
        }
    };

    private int settingsTaps = 0;      // v0.3.8：连点「待唤醒」大字 5 次进设置
    private long firstTapAt = 0L;

    /** v0.3.7：空白区域连点计数（5 秒窗口），满 5 次进设置 */
    private void handleSettingsTap() {
        long now = SystemClock.elapsedRealtime();
        if (now - firstTapAt > 5000L) {
            settingsTaps = 0;
        }
        if (settingsTaps == 0) {
            firstTapAt = now;
        }
        settingsTaps++;
        if (settingsTaps >= 5) {
            settingsTaps = 0;
            startActivity(new android.content.Intent(ScreenUI.this,
                    SettingsUI.class));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(savedInstanceState);
        // v0.3.8：防御 —— 服务被杀（force-stop/系统回收）后只开 Activity 会出现
        // 「界面在、服务死了」，唤醒/播控全废。检测到服务未就绪就顺手拉起。
        if (SessionController.I == null) {
            try {
                startService(new android.content.Intent(this,
                        Ldyb5ClientService.class));
            } catch (Throwable ignore) {
            }
        }
        buildUi();
        refreshList();
        // v0.3.5：天气后台轮询（30 分钟一次，可设置手动位置）
        WeatherClient.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    // ---------------- UI 构建（全程序化，无 res 布局） ----------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                v, getResources().getDisplayMetrics());
    }

    private Button btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(C_TEXT);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setBackgroundColor(C_BTN);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams stretch() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams stretchH() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private TextView caption(String s, int color, float sizeSp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BODY);
        bodyRoot = root;

        // v0.3.9：设置入口 = 连点任意空白区域 5 次（5 秒内）。root 收到的 onClick
        // 都是「没有任何子 View 消费」的空白点击；按钮/日期天气行/电台列表项不计数
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                handleSettingsTap();
            }
        });

        // v0.3.6：顶栏整个去掉（设置入口见上方 root 连点）
        // ================= 双栏主体 =================
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(10), dp(8), dp(10), dp(8));

        // ---------- 左栏：时间/日期/天气 + 状态 + 播放控制 ----------
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        // v0.3.6：时间大字（≈1/4 屏宽，字号可自定义）+ 日期天气行 + 设备状态行
        clockPanel = new LinearLayout(this);
        clockPanel.setOrientation(LinearLayout.VERTICAL);
        clockPanel.setPadding(dp(14), 0, dp(14), dp(6));
        // 「02:18」5 字形实测 ≈3.2 倍字号宽 → 目标屏宽 1/4
        float timeSp = getResources().getDisplayMetrics().widthPixels
                / 4f / 3.2f;
        timeView = caption("", C_TEXT, timeSp);
        timeView.setTypeface(Typeface.DEFAULT_BOLD);
        // v0.3.7：时间不挂点击监听 —— 点击自然冒泡到 root 计入空白连点
        clockPanel.addView(timeView);
        dateWxView = caption("", C_SUB, 18);
        dateWxView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showForecast();
            }
        });
        clockPanel.addView(dateWxView);
        // v0.3.6：设备温度/CPU/内存行
        statView = caption("", C_SUB, 16);
        clockPanel.addView(statView);
        left.addView(clockPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(C_CARD);
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        stateView = caption("待唤醒", C_ACCENT, 36);
        stateView.setTypeface(Typeface.DEFAULT_BOLD);
        // v0.3.9：状态大字不挂监听 —— 点击冒泡到 root 计入空白连点
        card.addView(stateView);
        // v0.3.3：对话记录头部行 —— 标题 + 清空按钮（顶部）
        LinearLayout cHead = new LinearLayout(this);
        cHead.setOrientation(LinearLayout.HORIZONTAL);
        cHead.setGravity(Gravity.CENTER_VERTICAL);
        cHead.setPadding(0, dp(4), 0, dp(4));
        TextView cTitle = caption("对话记录", C_SUB, 13);
        cHead.addView(cTitle, stretch());
        cHead.addView(btn("清空", new View.OnClickListener() {
            public void onClick(View v) {
                convo.clear();
                convoView.setText("");
            }
        }), new LinearLayout.LayoutParams(dp(84),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(cHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // v0.3.3：对话框固定尺寸、纵向占满剩余空间，可拖动回看
        convoView = caption("", C_TEXT, 15);
        convoView.setLineSpacing(dp(2), 1f);
        // v0.3.9：ScrollView 会吞掉触摸不冒泡到 root —— 给内层文本挂空白连点计数
        convoView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                handleSettingsTap();
            }
        });
        convoScroll = new ScrollView(this);
        convoScroll.addView(convoView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(convoScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        left.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout mcard = new LinearLayout(this);
        mcard.setOrientation(LinearLayout.VERTICAL);
        mcard.setBackgroundColor(C_CARD);
        mcard.setPadding(dp(14), dp(8), dp(14), dp(10));
        nowView = caption("♪ 未在播放", C_TEXT, 17);
        nowView.setTypeface(Typeface.DEFAULT_BOLD);
        nowView.setMaxLines(1);
        nowView.setEllipsize(TextUtils.TruncateAt.END);
        mcard.addView(nowView);
        // v0.3.26：**酷我音乐**状态行。原先屏幕只有电台的状态（nowView），
        // 酷我在放什么/暂停没完全看不到 → 用户「分不清音乐是暂停还是播放」。
        // 这里单开一行显示酷我当前曲目 + 播放/暂停状态。
        kuwoView = caption("", C_SUB, 14);
        kuwoView.setMaxLines(1);
        kuwoView.setEllipsize(TextUtils.TruncateAt.END);
        mcard.addView(kuwoView);

        // v0.3.3：控制按钮全部移到右栏（电台一侧）顶部；音乐卡只留「正在播放」行

        left.addView(mcard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // v0.3.45：底部控制行 —— 唤醒（宽）+ 静音/恢复（窄），两个独立按钮。
        // 静音 = 立即掐掉当前会话与 TTS、KWS 停识别、释放麦克风（不再收音）；
        // 再点一次恢复收音（并自动重新开麦）。
        LinearLayout wakeRow = new LinearLayout(this);
        wakeRow.setOrientation(LinearLayout.HORIZONTAL);
        wakeRow.setGravity(Gravity.CENTER_VERTICAL);
        wakeRow.addView(btn("🎤 唤醒小智", new View.OnClickListener() {
            public void onClick(View v) {
                SessionController sc = SessionController.I;
                if (sc == null) {
                    Toast.makeText(ScreenUI.this, "服务未就绪", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!AppSettings.voiceEnabled(ScreenUI.this)) {
                    Toast.makeText(ScreenUI.this, "设置里已关闭小智，请先恢复",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (SessionController.userMuted) {
                    Toast.makeText(ScreenUI.this, "已静音，请先点「恢复」",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                sc.debugWakeup();
            }
        }), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wakeRow.addView(space(dp(6)));
        muteBtn = btn("🔇 静音", new View.OnClickListener() {
            public void onClick(View v) {
                // 「当前是否已静音」= 运行时开关 或 设置总开关关闭
                boolean muted = SessionController.isMutedNow(ScreenUI.this);
                if (muted) {
                    // 恢复：运行时开关复位；设置总开关若关着也一并打开（否则恢复不了）
                    SessionController.userMuted = false;
                    if (!AppSettings.voiceEnabled(ScreenUI.this)) {
                        AppSettings.setVoiceEnabled(ScreenUI.this, true);
                    } else {
                        SessionController sc = SessionController.I;
                        if (sc != null) {
                            sc.setUserMuted(false);
                        } else {
                            startService(new android.content.Intent(ScreenUI.this,
                                    Ldyb5ClientService.class));
                        }
                    }
                } else {
                    SessionController sc = SessionController.I;
                    if (sc != null) {
                        sc.setUserMuted(true);
                    } else {
                        SessionController.userMuted = true;
                        Toast.makeText(ScreenUI.this, "服务未就绪，已置静音",
                                Toast.LENGTH_SHORT).show();
                    }
                }
                updateMuteBtn();
            }
        });
        wakeRow.addView(muteBtn, new LinearLayout.LayoutParams(dp(116),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(wakeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 11f));

        body.addView(space(dp(10)));

        // ---------- 右栏：电台 ----------
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        // v0.3.3：控制按键移到电台一侧顶部 —— 音量- / 播放停止(合并) / 音量+
        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER_VERTICAL);
        ctrlRow.addView(btn("音量-", new View.OnClickListener() {
            public void onClick(View v) {
                stepVol(-2);
            }
        }), stretch());
        ctrlRow.addView(space(dp(6)));
        playBtn = btn("▶ 播放", new View.OnClickListener() {
            public void onClick(View v) {
                MusicPlayer mp = MusicPlayer.I;
                if (mp == null) return;
                // 播放/停止合并键：在播 -> 停止；空闲/暂停 -> 播放
                if (mp.playing && !mp.isPaused()) mp.stop();
                else mp.resume();
            }
        });
        ctrlRow.addView(playBtn, new LinearLayout.LayoutParams(dp(120),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ctrlRow.addView(space(dp(6)));
        ctrlRow.addView(btn("音量+", new View.OnClickListener() {
            public void onClick(View v) {
                stepVol(2);
            }
        }), stretch());
        right.addView(ctrlRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout libHead = new LinearLayout(this);
        libHead.setOrientation(LinearLayout.HORIZONTAL);
        libHead.setGravity(Gravity.CENTER_VERTICAL);
        libTitle = caption("电台", C_ACCENT, 18);
        libTitle.setTypeface(Typeface.DEFAULT_BOLD);
        libHead.addView(libTitle, stretch());
        // v0.2.5：曲库功能移除，右栏固定为电台列表（本地 radio.json 导入）
        right.addView(libHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adapter = new LibAdapter();
        libView = new ListView(this);
        libView.setAdapter(adapter);
        libView.setBackgroundColor(0x88101820);
        libView.setDividerHeight(1);
        libView.setPadding(dp(2), dp(2), dp(2), dp(2));
        libView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= items.size()) return;
                MusicPlayer mp = music();
                if (mp == null) return;
                mp.radio(items.get(pos));
            }
        });
        LinearLayout lvWrap = new LinearLayout(this);
        lvWrap.setOrientation(LinearLayout.VERTICAL);
        lvWrap.setPadding(0, dp(6), 0, 0);
        lvWrap.addView(libView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        right.addView(lvWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 9f));

        // v0.3.9：灵动岛改全局悬浮窗（IslandOverlay，服务持有）——
        // 覆盖任何界面，app 内不再内嵌一份，避免前台时双图标

        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private View space(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, 1));
        return v;
    }

    private MusicPlayer music() {
        MusicPlayer mp = MusicPlayer.I;
        if (mp == null) {
            Toast.makeText(this, "音乐服务未就绪", Toast.LENGTH_SHORT).show();
        }
        return mp;
    }

    /** v0.3.45：按运行时静音态刷新按钮文案/配色（只在状态变化时改，避免每 500ms 重设） */
    private void updateMuteBtn() {
        if (muteBtn == null) {
            return;
        }
        int want = SessionController.isMutedNow(this) ? 1 : 0;
        if (want == muteUiState) {
            return;
        }
        muteUiState = want;
        if (want == 1) {
            muteBtn.setText("🔊 恢复");
            muteBtn.setTextColor(C_HEADER);
            muteBtn.setBackgroundColor(C_BTN_ON);
        } else {
            muteBtn.setText("🔇 静音");
            muteBtn.setTextColor(C_TEXT);
            muteBtn.setBackgroundColor(C_BTN);
        }
    }

    private void stepVol(int d) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                Math.max(0, Math.min(max, cur + d)), 0);
        // v0.3.62：与物理音量键统一走仿系统音量条（固件无原生音量面板）
        VolBarOverlay.show(this);
    }

    // ---------------- 轮询刷新 ----------------

    private void refresh() {
        // v0.3.6：时间大字 + 日期星期 + 城市天气（WeatherClient 30 分钟轮询）
        Date now = new Date();
        timeView.setText(hm.format(now));
        String ds = dateFmt.format(now);
        String wx = WeatherClient.last;
        String ct = WeatherClient.city;
        StringBuilder sb = new StringBuilder(ds);
        if (ct.length() > 0) {
            sb.append("  ·  ").append(ct);
        }
        if (wx.length() > 0) {
            sb.append("  ").append(wx);
        }
        String want = sb.toString();
        if (!want.equals(dateWxView.getText().toString())) {
            dateWxView.setText(want);
        }
        // v0.3.6：显示自定义（字号/颜色/背景）每轮套用，设置保存后立即生效
        float ts = AppSettings.timeSize(this);
        if (ts <= 0f) {
            ts = getResources().getDisplayMetrics().widthPixels / 4f / 3.2f;
        }
        float dSz = AppSettings.dateSize(this);
        if (dSz <= 0f) {
            dSz = 18f;
        }
        timeView.setTextSize(ts);
        timeView.setTextColor(parseColor(AppSettings.timeColor(this), C_TEXT));
        dateWxView.setTextSize(dSz);
        statView.setTextSize(dSz);
        int dc = parseColor(AppSettings.dateColor(this), C_SUB);
        dateWxView.setTextColor(dc);
        statView.setTextColor(dc);
        clockPanel.setBackgroundColor(parseColor(AppSettings.clockBg(this), 0x00000000));
        // v0.3.9：主界面底色（保存显示设置后下轮 refresh 立即生效）
        bodyRoot.setBackgroundColor(parseColor(AppSettings.bodyBg(this), C_BODY));
        // v0.3.6：设备温度/CPU/内存，2 秒采样一次；设置里可关
        long nowMs = SystemClock.elapsedRealtime();
        if (AppSettings.statsOn(this)) {
            if (nowMs - lastStatsMs >= 2000L) {
                lastStatsMs = nowMs;
                statView.setText(sampleStats());
            }
        } else if (statView.length() > 0) {
            statView.setText("");
        }
        stateView.setText(ScreenBus.state);
        // v0.3.45：静音按钮跟随真实状态（设置里关掉小智时也显示「恢复」）
        updateMuteBtn();
        // v0.3.0：对话历史 —— STT/LLM 值变化即追加，最多保留 CONVO_MAX_LINES 行
        String stt = ScreenBus.stt;
        String llm = ScreenBus.llm;
        if (stt != null && stt.length() > 0 && !stt.equals(lastStt)) {
            lastStt = stt;
            convo.add("我：" + stt);
        }
        if (llm != null && llm.length() > 0 && !llm.equals(lastLlm)) {
            lastLlm = llm;
            convo.add("小智：" + llm);
        }
        while (convo.size() > CONVO_MAX_LINES) {
            convo.remove(0);
        }
        // v0.3.3：仅内容变化时 setText + 滚底，否则用户拖动回看会被 500ms
        // 轮询不断拉回底部
        String joined = convo.isEmpty() ? "" : TextUtils.join("\n", convo);
        if (joined.length() > 0 && !joined.equals(convoView.getText().toString())) {
            convoView.setText(joined);
            convoScroll.post(new Runnable() {
                public void run() {
                    convoScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }

        MusicPlayer mp = MusicPlayer.I;
        boolean playing = mp != null && mp.playing && !mp.isPaused();
        // v0.3.5：暂停语义已并入停止，按钮文案同步为「停止」
        playBtn.setText(playing ? "⏹ 停止" : "▶ 播放");
        String cur = mp == null ? null : mp.currentTitle();
        if (cur != null ? !cur.equals(lastNow)
                : (lastNow != null || (mp != null && mp.isPaused()))) {
            lastNow = cur;
            nowView.setText(cur == null ? "♪ 未在播放" : "♪ " + cur);
        } else if (cur != null && lastNow == null) {
            lastNow = cur;
            nowView.setText("♪ " + cur);
        }

        // v0.3.26：酷我音乐状态行 —— 曲目 + 「正在播放 / 已暂停 / 已停止」。
        // 修「分不清音乐是暂停还是播放」：屏幕原先只有电台的状态，酷我完全不可见。
        String kuwoTitle = KuwoController.title;
        if (kuwoTitle == null || kuwoTitle.length() == 0) {
            if (lastKuwo != null) {
                lastKuwo = null;
                kuwoView.setText("");
            }
        } else {
            String kuwoState = KuwoController.isPlaying() ? "正在播放"
                    : KuwoController.isPaused() ? "已暂停" : "已停止";
            String kuwoArtist = (KuwoController.artist == null
                    || KuwoController.artist.length() == 0) ? "" : (" - " + KuwoController.artist);
            String kuwoLine = "♫ " + kuwoTitle + kuwoArtist + "（" + kuwoState + "）";
            if (!kuwoLine.equals(lastKuwo)) {
                lastKuwo = kuwoLine;
                kuwoView.setText(kuwoLine);
            }
        }

        // v0.3.12：黄标跟随当前台重画列表。旧实现只在 items 为空时填充一次，
        // 之后 notifyDataSetChanged 再也不调 → 黄标冻死在旧台上：语音换台/next/prev
        // 后播放行已是新台、黄标还停在旧台（2026-10-25 实测复现）。
        if (cur != null ? !cur.equals(lastMarker) : lastMarker != null) {
            lastMarker = cur;
            adapter.notifyDataSetChanged();
        }

        // 列表填充：首次就绪，或电台数量变化（radio.json 重载过）→ 重填
        if (ScreenBus.musicReady && mp != null
                && ScreenBus.musicTick != lastMusicTick
                && (items.isEmpty() || items.size() != mp.radioNames().size())) {
            refreshList();
        }
    }

    /** v0.3.6：hex 颜色解析（#RRGGBB / #AARRGGBB），无效返回 def */
    private static int parseColor(String s, int def) {
        if (s == null || s.length() == 0) {
            return def;
        }
        try {
            return Color.parseColor(s.startsWith("#") ? s : "#" + s);
        } catch (Throwable t) {
            return def;
        }
    }

    /** v0.3.6：采样 CPU 占用（/proc/stat 差分）+ 内存 + 温度 */
    private String sampleStats() {
        // CPU：/proc/stat 首行 cpu  user nice system idle iowait irq softirq ...
        long[] cur = readProcStat();
        int cpuPct = -1;
        if (cur != null && lastCpuTotal > 0) {
            long dTotal = cur[0] - lastCpuTotal;
            long dIdle = cur[1] - lastCpuIdle;
            if (dTotal > 0) {
                cpuPct = (int) ((dTotal - dIdle) * 100 / dTotal);
            }
        }
        if (cur != null) {
            lastCpuTotal = cur[0];
            lastCpuIdle = cur[1];
        }
        // 内存：ActivityManager.MemoryInfo
        int memPct = -1;
        try {
            android.app.ActivityManager.MemoryInfo mi =
                    new android.app.ActivityManager.MemoryInfo();
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(Context.ACTIVITY_SERVICE);
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0) {
                memPct = (int) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem);
            }
        } catch (Throwable ignore) {
        }
        // 温度：thermal_zone0（毫摄氏度）→ 失败回退电池温度
        double temp = readThermalTemp();
        StringBuilder sb = new StringBuilder();
        if (cpuPct >= 0) {
            sb.append("CPU ").append(cpuPct).append("%");
        }
        if (memPct >= 0) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append("内存 ").append(memPct).append("%");
        }
        if (temp > -50) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append((int) Math.round(temp)).append("°C");
        }
        return sb.length() == 0 ? "" : sb.toString();
    }

    /** @return {total, idle}，失败 null */
    private long[] readProcStat() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/stat"), 256);
            String line = r.readLine();
            r.close();
            if (line == null || !line.startsWith("cpu ")) {
                return null;
            }
            String[] p = line.split("\\s+");
            long total = 0, idle = 0;
            for (int i = 1; i < p.length; i++) {
                long v = Long.parseLong(p[i]);
                total += v;
                if (i == 4 || i == 5) { // idle + iowait
                    idle += v;
                }
            }
            return new long[]{total, idle};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 温度：优先 CPU 温区（type 含 cpu），回退 zone0 / 电池温度；读不到返回 -100 */
    private double readThermalTemp() {
        // v0.3.6：zone0 是 SoC 综合温区（mtktswmt），CPU 核温在 mtktscpu —— 先扫 type
        java.io.File tzDir = new java.io.File("/sys/class/thermal");
        java.io.File[] zones = tzDir.listFiles();
        if (zones != null) {
            String best = null, fallback = null;
            for (java.io.File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) {
                    continue;
                }
                try {
                    java.io.BufferedReader tr = new java.io.BufferedReader(
                            new java.io.FileReader(
                                    new java.io.File(z, "type")), 64);
                    String type = tr.readLine();
                    tr.close();
                    java.io.File tempF = new java.io.File(z, "temp");
                    if (type != null && type.contains("cpu")) {
                        best = tempF.getAbsolutePath();
                    } else if (fallback == null) {
                        fallback = tempF.getAbsolutePath();
                    }
                } catch (Throwable ignore) {
                }
            }
            String pick = best != null ? best : fallback;
            if (pick != null) {
                double v = readMilliTemp(pick);
                if (v > -50) {
                    return v;
                }
            }
        }
        try {
            android.content.Intent bat = registerReceiver(null,
                    new android.content.IntentFilter(
                            android.content.Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int t = bat.getIntExtra("temperature", -999);
                if (t > -200) {
                    return t / 10.0;
                }
            }
        } catch (Throwable ignore) {
        }
        return -100;
    }

    private static double readMilliTemp(String path) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(path), 64);
            String s = r.readLine();
            r.close();
            double v = Double.parseDouble(s.trim());
            if (v > 1000) {
                v /= 1000;
            }
            if (v > -20 && v < 120) {
                return v;
            }
        } catch (Throwable ignore) {
        }
        return -100;
    }

    /** v0.3.6：点击日期天气行 → 未来几天预报弹窗 */
    private void showForecast() {
        String[] fc = WeatherClient.forecast;
        StringBuilder sb = new StringBuilder();
        for (String s : fc) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(s);
        }
        if (sb.length() == 0) {
            sb.append("暂无预报数据");
        }
        String title = WeatherClient.city.length() > 0
                ? "天气预报 · " + WeatherClient.city : "天气预报";
        new android.app.AlertDialog.Builder(ScreenUI.this)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void refreshList() {
        MusicPlayer mp = MusicPlayer.I;
        if (mp == null) return;
        items = new ArrayList<String>(mp.radioNames());
        lastMusicTick = ScreenBus.musicTick;
        libTitle.setText("电台 " + items.size() + " 个");
        adapter.notifyDataSetChanged();
    }

    private class LibAdapter extends BaseAdapter {
        public int getCount() {
            return items.size();
        }

        public Object getItem(int position) {
            return items.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(ScreenUI.this);
                tv.setTextColor(C_TEXT);
                tv.setTextSize(16);
                tv.setPadding(dp(14), dp(9), dp(14), dp(9));
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
            }
            tv.setBackgroundColor(position % 2 == 0 ? 0x40FFFFFF : 0x00000000);
            String s = items.get(position);
            MusicPlayer mp = MusicPlayer.I;
            String cur = mp == null ? null : mp.currentTitle();
            if (cur != null && cur.equals(s)) {
                tv.setText("▶ " + s);
                tv.setTextColor(C_ACCENT);
            } else {
                tv.setText(s);
                tv.setTextColor(C_TEXT);
            }
            return tv;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            // v0.3.62：固件无系统音量面板，自绘音量条。
            // v0.3.65：统一走 VolBarOverlay.adjustAndShow，与全局 MediaSession 路径去重，避免一次按键变两档。
            int dir = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                    ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            VolBarOverlay.adjustAndShow(this, dir);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
