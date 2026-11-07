package cn.workbuddy.ldyb5app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ldyb5 v0.3.0：设置界面（横屏，风格与 ScreenUI 一致）。
 *
 * 即时生效：提示音 / 丢弃唤醒词 / 音乐让路 / 唤醒亮屏 / 60s 空闲断开 / 启用小智。
 * 自动重启服务后生效并提示完成：唤醒词（KWS 引擎初始化）、唤醒灵敏度；
 * 服务器地址走热重连（OTA 在启动时打）。
 * 电台 JSON：从存储选文件 → 拷到 app 外部私有目录 radio.json → 立即重载。
 */
public class SettingsUI extends Activity {

    private static final int C_BG = 0xFF16222C;
    private static final int C_CARD = 0xFF1E2E3A;
    private static final int C_ACCENT = 0xFFF7CF4E;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_SUB = 0xFFCDE9F2;
    private static final int C_BTN = 0xFF616161;

    private static final int REQ_RADIO_JSON = 41;

    private static final int dp1 = 4; // 粗略 dp（本机密度低，1dp≈1px 也够用）

    private TextView radioCountView;
    private EditText otaBox;
    private EditText tokenBox;
    private EditText wakeBox;
    private EditText wxBox;   // v0.3.6：天气手动位置
    // v0.3.9：字号改拖动条、颜色改可视化色块
    private SeekBar timeSizeBar;
    private CheckBox timeAutoBox;
    private TextView timeSizeVal;
    private SeekBar dateSizeBar;
    private TextView dateSizeVal;
    private String timeColorSel = "";   // 当前选中的颜色值（"" = 默认）
    private String dateColorSel = "";
    private String bgSel = "";
    private String bodySel = "";        // v0.3.9：主界面底色
    private Button timeColorBtn;
    private Button dateColorBtn;
    private Button bgBtn;
    private Button bodyBtn;
    private TextView srvModeView;

    // v0.3.53：灵敏度保存 → 等服务真正重新装配完成再提示，不盲等固定延迟
    private final Handler restartHandler = new Handler(Looper.getMainLooper());
    private Runnable restartTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(savedInstanceState);
        // v0.3.3：进设置不自动弹软键盘（首个 EditText 抢焦点导致）
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        buildUi();
    }

    // v0.3.10：离开设置页恢复灵动岛自动显隐（预览关）
    @Override
    protected void onPause() {
        IslandOverlay.setPreview(false);
        super.onPause();
    }

    private LinearLayout row(String label, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundColor(C_CARD);
        r.setPadding(12 * dp1, 7 * dp1, 12 * dp1, 7 * dp1);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(C_TEXT);
        t.setTextSize(16);
        r.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(C_SUB);
            s.setTextSize(12);
            r.addView(s);
        }
        return r;
    }

    private LinearLayout withSwitch(LinearLayout r, boolean on,
                                    CompoundButton.OnCheckedChangeListener l) {
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setBackgroundColor(C_CARD);
        row2.setPadding(12 * dp1, 7 * dp1, 12 * dp1, 7 * dp1);
        LinearLayout textWrap = new LinearLayout(this);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        textWrap.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        while (r.getChildCount() > 0) {
            View v = r.getChildAt(0);
            r.removeViewAt(0);
            textWrap.addView(v);
        }
        row2.addView(textWrap);
        row2.addView(sw, lp);
        return row2;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_ACCENT);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(12 * dp1, 10 * dp1, 12 * dp1, 4 * dp1);
        return t;
    }

    private Button btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(C_TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackgroundColor(C_BTN);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(10 * dp1, 10 * dp1, 10 * dp1, 20 * dp1);

        // ---------------- 顶栏 ----------------
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(4 * dp1, 0, 4 * dp1, 8 * dp1);
        TextView title = new TextView(this);
        title.setText("⚙ 小智设置");
        title.setTextColor(C_ACCENT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // V1.0.1：版本号紧贴「小智设置」标题右侧，直接读 APK 自身 versionName。
        // 正式版显示 V1.0.1，内测版仍显示 0.x（两条版本线共用同一份代码）。
        TextView ver = new TextView(this);
        ver.setText(versionLabel());
        ver.setTextColor(C_SUB);
        ver.setTextSize(12);
        ver.setPadding(8 * dp1, 0, 0, 0);
        head.addView(ver, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View spacer = new View(this);
        head.addView(spacer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(btn("返回", new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        }), new LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------------- 语音 ----------------
        root.addView(section("语音"));

        // 启用小智
        root.addView(withSwitch(row("启用小智语音",
                        "关闭后不再响应唤醒词；电台/酷我播放不受影响"),
                AppSettings.voiceEnabled(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setVoiceEnabled(SettingsUI.this, v);
                        toast(v ? "小智已启用" : "小智已关闭（电台不受影响）");
                    }
                }));

        // 唤醒词（v0.3.2：任意输入，拼音自动转音素）
        LinearLayout wwRow = row("唤醒词",
                "任意 1~8 个汉字；预设可直接填：小智小智 / 小讯小讯 / 你好小智 / 天猫精灵");
        wakeBox = new EditText(this);
        wakeBox.setSingleLine();
        wakeBox.setTextColor(C_TEXT);
        wakeBox.setHintTextColor(0xFF9AB8C4);
        wakeBox.setHint("小智小智");
        wakeBox.setText(AppSettings.wakeWord(this));
        wakeBox.setTextSize(14);
        wwRow.addView(wakeBox);
        wwRow.addView(btn("保存唤醒词", new View.OnClickListener() {
            public void onClick(View v) {
                String nw = wakeBox.getText().toString().trim();
                AppSettings.setWakeWord(SettingsUI.this, nw);
                String saved = AppSettings.wakeWord(SettingsUI.this);
                wakeBox.setText(saved);
                toast("唤醒词已设为「" + saved + "」，正在重启小智…");
                // v0.3.54：与灵敏度一致 —— 自动重启服务，并在引擎重建完成后再提示
                armRestartReadyNotice("小智服务已重启完成，唤醒词「" + saved + "」已生效");
                restartVoiceService();
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(wwRow);

        // 提示音
        root.addView(withSwitch(row("提示音", "唤醒时的一声「叮」、退出小智时的「叮-咚」"),
                AppSettings.cueOn(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setCueOn(SettingsUI.this, v);
                    }
                }));

        // 音乐让路
        root.addView(withSwitch(row("音乐为小智让路",
                        "小智说话时暂停酷我音乐，说完自动恢复"),
                AppSettings.yieldMusic(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setYieldMusic(SettingsUI.this, v);
                    }
                }));

        // v0.3.21：唤醒词打断（本地 KWS 常驻，播放中喊「小智小智」强制打断）
        root.addView(withSwitch(row("唤醒词打断",
                        "小智说话时喊「小智小智」即可打断（本地 KWS，无需服务器 AEC）"),
                AppSettings.kwsInterrupt(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setKwsInterrupt(SettingsUI.this, v);
                        KwsEngine.pauseInTts = !v;
                    }
                }));

        // v1.3.60：本地唤醒后交接拾音（唤醒词不上传）
        root.addView(withSwitch(row("唤醒词不上传",
                        "本地 KWS 唤醒后，唤醒词本身不上传云端（小智不会回问候 TTS、"
                                + "也不用等问候播完）；唤醒后稍停顿一下再开口，云端只听到你的指令。"
                                + "关闭则唤醒词+指令一并即时上送、无缝"),
                AppSettings.serverListen(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setServerListen(SettingsUI.this, v);
                        toast(v ? "已开启：唤醒词不上传（唤醒后稍顿再开口）"
                                : "已关闭：唤醒词随指令一并上送");
                    }
                }));

        // v0.3.52：KWS 唤醒灵敏度（阈值 + 关键词加权分，两者协同才有效）
        LinearLayout sensRow = row("唤醒灵敏度",
                "越大越容易喊醒，也越容易误唤醒；默认「标准」。KWS 引擎参数需重启小智服务后生效");
        final int sensCur = AppSettings.kwsSensitivity(this);
        final TextView sensVal = sideLabel("当前：" + AppSettings.KWS_SENS_NAMES[sensCur]
                + "（阈值 " + AppSettings.KWS_SENS_THR[sensCur]
                + " / 加权 " + AppSettings.KWS_SENS_SCORE[sensCur] + "）");
        sensRow.addView(sensVal);
        final SeekBar sensBar = new SeekBar(this);
        sensBar.setMax(AppSettings.KWS_SENS_NAMES.length - 1);
        sensBar.setProgress(sensCur);
        final int[] sensPick = new int[]{sensCur};
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                sensPick[0] = Math.max(0,
                        Math.min(AppSettings.KWS_SENS_NAMES.length - 1, p));
                sensVal.setText("当前：" + AppSettings.KWS_SENS_NAMES[sensPick[0]]
                        + "（阈值 " + AppSettings.KWS_SENS_THR[sensPick[0]]
                        + " / 加权 " + AppSettings.KWS_SENS_SCORE[sensPick[0]] + "）");
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        sensRow.addView(sensBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sensRow.addView(btn("保存灵敏度并重启小智", new View.OnClickListener() {
            public void onClick(View v) {
                AppSettings.setKwsSensitivity(SettingsUI.this, sensPick[0]);
                AppSettings.applyKwsSensitivity(SettingsUI.this);
                toast("灵敏度已设为「" + AppSettings.KWS_SENS_NAMES[sensPick[0]]
                        + "」，正在重启小智…");
                armRestartReadyNotice("小智服务已重启完成，灵敏度已生效");
                restartVoiceService();
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sensRow);

        // 唤醒亮屏
        root.addView(withSwitch(row("唤醒小智亮屏", "熄屏状态喊唤醒词会点亮屏幕"),
                AppSettings.wakeScreen(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setWakeScreen(SettingsUI.this, v);
                    }
                }));

        // 60s 空闲断开
        root.addView(withSwitch(row("60 秒空闲自动断开",
                        "会话内 60 秒没人说话也没回复就断开连接"),
                AppSettings.idleDisconnect60(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setIdleDisconnect60(SettingsUI.this, v);
                    }
                }));

        // ---------------- 电台 ----------------
        root.addView(section("电台"));

        LinearLayout radioRow = row("电台列表 JSON", null);
        radioCountView = new TextView(this);
        radioCountView.setTextColor(C_SUB);
        radioCountView.setTextSize(12);
        radioCountView.setText(currentRadioText());
        radioRow.addView(radioCountView);
        LinearLayout radioBtns = new LinearLayout(this);
        radioBtns.setOrientation(LinearLayout.HORIZONTAL);
        radioBtns.addView(btn("导入 JSON", new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(
                            Intent.createChooser(i, "选择电台 radio.json"),
                            REQ_RADIO_JSON);
                } catch (Throwable t) {
                    toast("没有可用的文件选择器；可 adb push radio.json 到 "
                            + getExternalFilesDir(null));
                }
            }
        }), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        radioBtns.addView(btn("重新加载", new View.OnClickListener() {
            public void onClick(View v) {
                reloadRadio();
            }
        }), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // v0.3.1 修复：addView 之前 getLayoutParams() 返回 null，直接写 topMargin 必 NPE
        LinearLayout.LayoutParams radioBtnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        radioBtnsLp.topMargin = 4 * dp1;
        radioRow.addView(radioBtns, radioBtnsLp);
        root.addView(radioRow);

        // ---------------- 服务器 ----------------
        root.addView(section("服务器（保存/切换后自动重连，无需重启）"));
        // v0.3.2：两档切换 官方 / 自定义
        LinearLayout srvRow = row("服务器选择", srvDesc(AppSettings.srvMode(this)));
        srvModeView = (TextView) srvRow.getChildAt(1);
        srvRow.addView(btn("切换", new View.OnClickListener() {
            public void onClick(View v) {
                int nm = (AppSettings.srvMode(SettingsUI.this) + 1) % 2;
                AppSettings.setSrvMode(SettingsUI.this, nm);
                srvModeView.setText(srvDesc(nm));
                applyServerSettings();
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(srvRow);

        LinearLayout otaRow = row("自定义地址", "仅「自定义」模式使用");
        otaBox = new EditText(this);
        otaBox.setSingleLine();
        otaBox.setTextColor(C_TEXT);
        otaBox.setHintTextColor(0xFF9AB8C4);
        otaBox.setHint("http://192.168.2.189:8003/xiaozhi/ota/");
        otaBox.setText(AppSettings.otaUrl(this));
        otaBox.setTextSize(13);
        otaRow.addView(otaBox);
        root.addView(otaRow);

        // v0.3.1：手动 token（填了则优先于 OTA 下发）
        LinearLayout tokenRow = row("连接 Token",
                "留空 = 自动用服务器 OTA 下发的 token；填了则覆盖它");
        tokenBox = new EditText(this);
        tokenBox.setSingleLine();
        tokenBox.setTextColor(C_TEXT);
        tokenBox.setHintTextColor(0xFF9AB8C4);
        tokenBox.setHint("留空自动获取");
        tokenBox.setText(AppSettings.tokenCustom(this));
        tokenBox.setTextSize(13);
        tokenRow.addView(tokenBox);
        root.addView(tokenRow);

        root.addView(btn("保存服务器设置", new View.OnClickListener() {
            public void onClick(View v) {
                AppSettings.setOtaUrl(SettingsUI.this,
                        otaBox.getText().toString());
                AppSettings.setTokenCustom(SettingsUI.this,
                        tokenBox.getText().toString());
                applyServerSettings();
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------------- 天气 ----------------
        root.addView(section("天气（主界面点日期天气行可看未来几天预报）"));

        // v0.3.6：手动位置（空 = IP 自动定位）
        LinearLayout wxRow = row("天气位置",
                "填城市名（如：广州）；留空 = 按 IP 自动定位");
        wxBox = new EditText(this);
        wxBox.setSingleLine();
        wxBox.setTextColor(C_TEXT);
        wxBox.setHintTextColor(0xFF9AB8C4);
        wxBox.setHint("留空自动定位");
        wxBox.setText(AppSettings.wxCity(this));
        wxBox.setTextSize(14);
        wxRow.addView(wxBox);
        wxRow.addView(btn("保存位置", new View.OnClickListener() {
            public void onClick(View v) {
                AppSettings.setWxCity(SettingsUI.this,
                        wxBox.getText().toString());
                WeatherClient.reload();
                toast("已保存，天气按新位置刷新");
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(wxRow);

        // ---------------- 时钟/天气显示 ----------------
        root.addView(section("时钟显示（主界面立即生效）"));
        root.addView(withSwitch(row("显示设备状态",
                        "CPU 占用 / 内存占用 / CPU 温度"),
                AppSettings.statsOn(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setStatsOn(SettingsUI.this, v);
                    }
                }));
        // v0.3.9：灵动岛开关（小智机器人浮条）
        root.addView(withSwitch(row("灵动岛小智机器人",
                        "小智聆听/说话时，屏幕顶部中央显示会动的小机器人"),
                AppSettings.islandOn(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setIslandOn(SettingsUI.this, v);
                    }
                }));

        // v0.3.10：灵动岛大小 —— 拖动条 48~192dp，拖动时悬浮窗实时预览
        final int curI = AppSettings.islandSize(this);
        LinearLayout islandRow = new LinearLayout(this);
        islandRow.setOrientation(LinearLayout.HORIZONTAL);
        islandRow.setGravity(Gravity.CENTER_VERTICAL);
        islandRow.setBackgroundColor(C_CARD);
        islandRow.setPadding(12 * dp1, 7 * dp1, 12 * dp1, 7 * dp1);
        TextView islandLb = new TextView(this);
        islandLb.setText("大小");
        islandLb.setTextColor(C_TEXT);
        islandLb.setTextSize(16);
        final TextView islandVal = new TextView(this);
        islandVal.setText(curI + " dp");
        islandVal.setTextColor(C_SUB);
        islandVal.setTextSize(14);
        SeekBar islandBar = new SeekBar(this);
        islandBar.setMax(144);              // progress+48 = dp，范围 48~192
        islandBar.setProgress(curI - 48);
        islandRow.addView(islandLb);
        islandRow.addView(islandBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        islandRow.addView(islandVal);
        root.addView(islandRow);
        islandBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + 48;
                islandVal.setText(v + " dp");
                AppSettings.setIslandSize(SettingsUI.this, v);
                IslandOverlay.setSizeDp(v);   // 实时缩放，松手前就能看到
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        // v1.3.51：灵动岛透明度 —— 拖动条 20%~100%，实时预览
        final float curA = AppSettings.islandAlpha(this);
        LinearLayout alphaRow = new LinearLayout(this);
        alphaRow.setOrientation(LinearLayout.HORIZONTAL);
        alphaRow.setGravity(Gravity.CENTER_VERTICAL);
        alphaRow.setBackgroundColor(C_CARD);
        alphaRow.setPadding(12 * dp1, 7 * dp1, 12 * dp1, 7 * dp1);
        TextView alphaLb = new TextView(this);
        alphaLb.setText("透明度");
        alphaLb.setTextColor(C_TEXT);
        alphaLb.setTextSize(16);
        final TextView alphaVal = new TextView(this);
        alphaVal.setText(Math.round(curA * 100) + "%");
        alphaVal.setTextColor(C_SUB);
        alphaVal.setTextSize(14);
        SeekBar alphaBar = new SeekBar(this);
        alphaBar.setMax(80);                 // progress*0.01 + 0.20 = alpha，范围 0.20~1.00
        alphaBar.setProgress(Math.round((curA - 0.2f) * 100f));
        alphaRow.addView(alphaLb);
        alphaRow.addView(alphaBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        alphaRow.addView(alphaVal);
        root.addView(alphaRow);
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float a = 0.2f + p * 0.01f;
                alphaVal.setText(Math.round(a * 100) + "%");
                AppSettings.setIslandAlpha(SettingsUI.this, a);
                IslandOverlay.setAlpha(a);   // 实时预览
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        // v1.3.51：位置提示 + 重置按钮（拖动只在设置页预览期间有效）
        root.addView(row("位置",
                "在本设置页中直接拖动小智图标即可改变位置；"
                        + "平时图标不响应触摸，不影响点击和通知栏下拉"));
        root.addView(btn("↺ 重置图标位置（顶部居中）", new View.OnClickListener() {
            public void onClick(View v) {
                IslandOverlay.resetPosition();
                toast("已重置到顶部居中");
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(buildClockStyleRows());

        // ---------------- 其他 ----------------
        root.addView(section("其他"));

        // v0.3.6：root 开关（root 读酷我收藏库等功能）
        root.addView(withSwitch(row("Root 功能",
                        "关闭后不再调用 su（如 root 读酷我收藏库，改为跳收藏页手点）"),
                AppSettings.rootEnabled(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean v) {
                        AppSettings.setRootEnabled(SettingsUI.this, v);
                        toast(v ? "Root 功能已开启" : "Root 功能已关闭");
                    }
                }));

        // v0.3.9：退出小智 —— 停服务 + 杀进程完全关闭
        root.addView(btn("⏻ 退出小智（完全关闭）", new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsUI.this)
                        .setTitle("退出小智")
                        .setMessage("将停止语音服务、麦克风监听和音乐播放，"
                                + "并完全关闭小智 app。\n重新进入 app 或重启设备后可再次使用。")
                        .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                exitApp();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // V1.0.1：设置页底部项目地址（可点开浏览器）
        TextView proj = new TextView(this);
        proj.setText("项目地址：https://github.com/shj4588/LDY-B5_XiaoZhi_Client");
        proj.setTextColor(C_ACCENT);
        proj.setTextSize(12);
        proj.setPadding(4 * dp1, 18 * dp1, 4 * dp1, 10 * dp1);
        proj.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/shj4588/LDY-B5_XiaoZhi_Client")));
                } catch (Throwable t) {
                    toast("没有可用的浏览器打开项目地址");
                }
            }
        });
        root.addView(proj, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    /** 读 APK 自身 versionName：正式版 V1.0.1 / 内测版 0.x，不需要维护第二份常量。 */
    private String versionLabel() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * v0.3.52 / v0.3.54：重启语音服务，让 KwsEngine.init() 用新的唤醒词 /
     * threshold / score 重建 KeywordSpotter（灵敏度和唤醒词保存共用此入口）。
     * 停服务后延迟 1.2s 再拉起，
     * 避开 onDestroy 释放麦克风/WS 与 onCreate 重新申请的竞态。
     */
    private void restartVoiceService() {
        try {
            stopService(new android.content.Intent(this, Ldyb5ClientService.class));
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        public void run() {
                            try {
                                startService(new android.content.Intent(SettingsUI.this,
                                        Ldyb5ClientService.class));
                            } catch (Throwable ignored) {
                            }
                        }
                    }, 1200L);
        } catch (Throwable t) {
            cancelRestartNotice();
            toast("自动重启失败，请退出小智后重新进入");
        }
    }

    /**
     * v0.3.53 / v0.3.54：挂「服务已重启完成」回调（灵敏度、唤醒词共用）。
     * 服务侧在 buildPipeline 完成、且新 KeywordSpotter 建好后触发一次；
     * 25s 兜底防止 native 卡死时界面永远停在「正在重启」。
     */
    private void armRestartReadyNotice(final String readyMsg) {
        cancelRestartNotice();
        final boolean[] done = new boolean[]{false};
        Ldyb5ClientService.onRestartReady = new Runnable() {
            public void run() {
                finishRestartNotice(done, readyMsg);
            }
        };
        Ldyb5ClientService.restartNotifyPending = true;
        restartTimeout = new Runnable() {
            public void run() {
                finishRestartNotice(done, "小智服务重启超时，请退出小智后重新进入");
            }
        };
        restartHandler.postDelayed(restartTimeout, 25000L);
    }

    private void finishRestartNotice(boolean[] done, String msg) {
        if (done[0]) {
            return;
        }
        done[0] = true;
        cancelRestartNotice();
        if (!isFinishing()) {
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        }
    }

    private void cancelRestartNotice() {
        if (restartTimeout != null) {
            restartHandler.removeCallbacks(restartTimeout);
            restartTimeout = null;
        }
        Ldyb5ClientService.onRestartReady = null;
        Ldyb5ClientService.restartNotifyPending = false;
    }

    @Override
    protected void onDestroy() {
        cancelRestartNotice();
        super.onDestroy();
    }

    /** v0.3.3：服务器设置变更 -> 通知服务热重连（无需重启） */
    private void applyServerSettings() {
        Runnable r = Ldyb5ClientService.onServerSettingsChanged;
        if (r != null) {
            r.run();
            toast("已应用，正在重连服务器…");
        } else {
            toast("服务未就绪，重启设备后生效");
        }
    }

    /** v0.3.9：完全退出 —— 停播/播退出提示音/停服务/关界面/杀进程 */
    private void exitApp() {
        try {
            MusicPlayer mp = MusicPlayer.I;
            if (mp != null) {
                mp.stop();
            }
        } catch (Throwable ignore) {
        }
        // v0.3.9：退出提示音（与唤醒提示音同一开关）先播完再杀进程，否则音频被砍
        final boolean cue = AppSettings.cueOn(this);
        final Runnable kill = new Runnable() {
            public void run() {
                try {
                    stopService(new Intent(SettingsUI.this, Ldyb5ClientService.class));
                } catch (Throwable ignore) {
                }
                try {
                    ScreenBus.state = "已退出";
                } catch (Throwable ignore) {
                }
                finishAffinity();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        };
        if (cue) {
            SessionController.playExitCue();
            new Handler(Looper.getMainLooper()).postDelayed(kill, 800L);
        } else {
            kill.run();
        }
    }

    /** v0.3.9：时钟区字号改拖动条、颜色改可视化色块 */
    private LinearLayout buildClockStyleRows() {
        LinearLayout box = row("时间 / 日期字号 与 颜色",
                "字号拖动设置（勾「自动」= 1/4 屏宽）；点色块选颜色，选「默认」恢复");

        // ---- 时间字号：自动开关 + 拖动条 16~160 ----
        float curT = AppSettings.timeSize(this);
        timeAutoBox = new CheckBox(this);
        timeAutoBox.setText("自动(1/4屏宽)");
        timeAutoBox.setTextColor(C_TEXT);
        timeAutoBox.setTextSize(14);
        timeAutoBox.setChecked(curT <= 0);
        box.addView(timeAutoBox);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeSizeBar = new SeekBar(this);
        timeSizeBar.setMax(144);          // progress+16 = sp，范围 16~160（160≈1/2屏宽）
        timeSizeBar.setProgress(curT <= 0 ? 64 : Math.round(curT) - 16);
        timeSizeBar.setEnabled(!timeAutoBox.isChecked());
        timeSizeVal = sideLabel(curT <= 0 ? "80 sp" : Math.round(curT) + " sp");
        timeRow.addView(timeSizeBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        timeRow.addView(timeSizeVal);
        box.addView(timeRow);
        timeAutoBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                timeSizeBar.setEnabled(!v);
            }
        });
        timeSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                timeSizeVal.setText((p + 16) + " sp");
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        // ---- 日期字号：拖动条 10~36 ----
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        dateRow.addView(sideLabel("日期"));
        dateSizeBar = new SeekBar(this);
        dateSizeBar.setMax(26);           // progress+10 = sp，范围 10~36
        dateSizeBar.setProgress(Math.round(AppSettings.dateSize(this)) - 10);
        dateSizeVal = sideLabel(Math.round(AppSettings.dateSize(this)) + " sp");
        dateRow.addView(dateSizeBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dateRow.addView(dateSizeVal);
        box.addView(dateRow);
        dateSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                dateSizeVal.setText((p + 10) + " sp");
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        // ---- 颜色：三个色块按钮，点击弹可视化选色 ----
        timeColorSel = AppSettings.timeColor(this);
        dateColorSel = AppSettings.dateColor(this);
        bgSel = AppSettings.clockBg(this);
        timeColorBtn = colorBtn("时间颜色", timeColorSel);
        timeColorBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showColorPicker("时间颜色", timeColorSel, timeColorBtn);
            }
        });
        dateColorBtn = colorBtn("日期颜色", dateColorSel);
        dateColorBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showColorPicker("日期颜色", dateColorSel, dateColorBtn);
            }
        });
        bgBtn = colorBtn("时钟背景", bgSel);
        bgBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showColorPicker("时钟背景", bgSel, bgBtn);
            }
        });
        box.addView(timeColorBtn);
        box.addView(dateColorBtn);
        box.addView(bgBtn);
        // v0.3.9：主界面底色（整个屏幕的背景）
        bodySel = AppSettings.bodyBg(this);
        bodyBtn = colorBtn("主界面背景", bodySel);
        bodyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showColorPicker("主界面背景", bodySel, bodyBtn);
            }
        });
        box.addView(bodyBtn);

        box.addView(btn("保存显示设置", new View.OnClickListener() {
            public void onClick(View v) {
                AppSettings.setTimeSize(SettingsUI.this,
                        timeAutoBox.isChecked() ? 0f : timeSizeBar.getProgress() + 16);
                AppSettings.setDateSize(SettingsUI.this,
                        dateSizeBar.getProgress() + 10);
                AppSettings.setTimeColor(SettingsUI.this, timeColorSel);
                AppSettings.setDateColor(SettingsUI.this, dateColorSel);
                AppSettings.setClockBg(SettingsUI.this, bgSel);
                AppSettings.setBodyBg(SettingsUI.this, bodySel);
                toast("已保存，返回主界面即可看到效果");
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** 拖动条旁的小标签 */
    private TextView sideLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_SUB);
        t.setTextSize(13);
        t.setMinWidth(26 * dp1);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** 颜色选择按钮：背景显示当前色，无色时灰色显示「默认」 */
    private Button colorBtn(String name, String cur) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setText(cur == null || cur.length() == 0
                ? name + "：默认" : name + "：" + cur);
        b.setTextColor(0xFF000000);
        b.setBackgroundColor(cur == null || cur.length() == 0
                ? C_BTN : parseColorSafe(cur, C_BTN));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4 * dp1, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** 可视化选色弹窗：预设色块网格 + 「默认」 */
    private void showColorPicker(final String name, final String cur,
            final Button target) {
        final String[] palette = {
                "#FFFFFF", "#C0C0C0", "#616161", "#000000", "#16222C",
                "#F7CF4E", "#E0A62A", "#FF8C00", "#FF5252", "#E91E63",
                "#9C27B0", "#3F51B5", "#2196F3", "#2FB4D8", "#00BCD4",
                "#4CAF50", "#8BC34A", "#FFEB3B",
        };
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(10 * dp1, 10 * dp1, 10 * dp1, 4 * dp1);
        final int perRow = 6;
        for (int i = 0; i < palette.length + 1; i += perRow) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + perRow && j < palette.length + 1; j++) {
                Button sw;
                if (j >= palette.length) {
                    // 末位「默认」块
                    sw = new Button(this);
                    sw.setText("默认");
                    sw.setTextSize(11);
                    sw.setTextColor(C_TEXT);
                    sw.setBackgroundColor(C_BTN);
                } else {
                    final String hex = palette[j];
                    sw = new Button(this);
                    sw.setBackgroundColor(Color.parseColor(hex));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(2 * dp1, 2 * dp1, 2 * dp1, 2 * dp1);
                sw.setLayoutParams(lp);
                final String sel = j >= palette.length ? "" : palette[j];
                sw.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if ("时间颜色".equals(name)) {
                            timeColorSel = sel;
                        } else if ("日期颜色".equals(name)) {
                            dateColorSel = sel;
                        } else if ("时钟背景".equals(name)) {
                            bgSel = sel;
                        } else {
                            bodySel = sel;
                        }
                        target.setText(name + "："
                                + (sel.length() == 0 ? "默认" : sel));
                        target.setBackgroundColor(sel.length() == 0
                                ? C_BTN : Color.parseColor(sel));
                        toast("已选择" + name + "，记得点保存");
                    }
                });
                r.addView(sw);
            }
            wrap.addView(r);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择" + name)
                .setView(wrap)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static int parseColorSafe(String s, int def) {
        try {
            return Color.parseColor(s.startsWith("#") ? s : "#" + s);
        } catch (Throwable t) {
            return def;
        }
    }

    private EditText hexBox(String value, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine();
        e.setTextColor(C_TEXT);
        e.setHintTextColor(0xFF9AB8C4);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(14);
        return e;
    }

    private static float parseSp(String s) {
        String t = s == null ? "" : s.trim().replace("sp", "").replace("SP", "");
        if (t.length() == 0) {
            return 0f;
        }
        return Float.parseFloat(t);
    }

    private String srvDesc(int mode) {
        if (mode == AppSettings.SRV_CUSTOM) {
            return "当前：自定义（用下方地址）";
        }
        return "当前：官方 api.tenclass.net";
    }

    private String currentRadioText() {
        MusicPlayer mp = MusicPlayer.I;
        int n = mp == null ? 0 : mp.radioNames().size();
        return "当前 " + n + " 个台；文件位置 "
                + getExternalFilesDir(null) + "/radio.json";
    }

    private void reloadRadio() {
        MusicPlayer mp = MusicPlayer.I;
        if (mp == null) {
            toast("音乐服务未就绪");
            return;
        }
        mp.loadRadiosAsync();
        Toast.makeText(this, "已重新加载，稍后生效", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_RADIO_JSON || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        File dst = new File(getExternalFilesDir(null), "radio.json");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            out = new java.io.FileOutputStream(dst, false);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            toast("已导入，正在重载电台列表");
            reloadRadio();
        } catch (Throwable t) {
            toast("导入失败：" + t);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v0.3.10：设置页打开期间强制显示灵动岛（拖动大小实时预览）
        IslandOverlay.setPreview(true);
        if (radioCountView != null) {
            radioCountView.setText(currentRadioText());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
