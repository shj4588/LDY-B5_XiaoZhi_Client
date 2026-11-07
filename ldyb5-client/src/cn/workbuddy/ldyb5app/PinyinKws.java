package cn.workbuddy.ldyb5app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;

/**
 * v0.3.2：任意汉字唤醒词 → sherpa KWS 关键词 token 行转换器。
 *
 * 模型 tokens.txt 的音素体系（wenetspeech 带调拼音，声母/韵母拆分）：
 *   声母：b p m f d t n l g k h j q x r z c s zh ch sh
 *   韵母：带调（ā á ǎ à、iǎo、uàn、ǜ ...），轻声无独立 token
 *
 * 输入：汉字串（任意词，2~8 字）
 * 输出："x iǎo zh ì x iǎo zh ì @小智小智" 这类 token 行，
 *       交给 KeywordSpotter.createStream(String)（sherpa 的关键词内容接口，
 *       非空时覆盖 keywords_file）。
 *
 * 规则要点：
 *   · y→i / w→u（yi→i、you→ou、wu→u、wei→ui、weng→eng 等正字法折叠）
 *   · j/q/x 后按书写形式用 u（juàn → j uàn）；l/n 后用 ü（lǜ → ǜ）
 *   · 声调标记位置：a > o > e > 末元音（标准标调规则）
 *   · 目标 token 不存在时按 [本调,3,2,4,1] 顺序找替调，全没有 = 该字不支持
 */
public final class PinyinKws {

    private static HashMap<String, String> table;   // 汉字 -> 带调数字拼音
    private static HashMap<String, Boolean> tokens; // 合法 token 集（含声母）

    private PinyinKws() {
    }

    private static synchronized void ensure(Context ctx) {
        if (table != null) {
            return;
        }
        table = new HashMap<String, String>(22000);
        tokens = new HashMap<String, Boolean>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("kws/pinyin_table.txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() < 3) {
                    continue;
                }
                table.put(line.substring(0, 1), line.substring(2).trim());
            }
            r.close();
            BufferedReader t = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("kws/tokens.txt"), "UTF-8"));
            while ((line = t.readLine()) != null) {
                String p = line.trim().split("\\s+")[0];
                if (p.length() > 0) {
                    tokens.put(p, Boolean.TRUE);
                }
            }
            t.close();
        } catch (Throwable t) {
            android.util.Log.e("L5CLIENT", "PinyinKws load failed", t);
            table = null;
            tokens = null;
        }
    }

    /**
     * @return token 行；任一字转不了 / 词长不合法返回 null（调用方回退默认词表）
     */
    public static String toKeywordLine(Context ctx, String word) {
        ensure(ctx);
        if (table == null || word == null) {
            return null;
        }
        word = word.trim();
        int n = word.codePointCount(0, word.length());
        if (n < 1 || n > 8) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < word.length()) {
            int cp = word.codePointAt(i);
            i += Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            String py = table.get(ch);
            if (py == null) {
                return null;
            }
            String[] toks = syllableToTokens(py);
            if (toks == null) {
                return null;
            }
            for (String t : toks) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        sb.append(" @").append(word);
        return sb.toString();
    }

    /** "zhi4" -> {"zh","ì"}；"yu2" -> {"ǘ"}；失败返回 null */
    public static String[] syllableToTokens(String py) {
        int tone = 0;
        String letters = py;
        char last = py.charAt(py.length() - 1);
        if (last >= '1' && last <= '5') {
            tone = last - '0';
            letters = py.substring(0, py.length() - 1);
        }
        if (letters.length() == 0) {
            return null;
        }
        // ---- 正字法折叠：y / w / yu ----
        if (letters.startsWith("yuan")) {
            letters = "uan" + letters.substring(4);
        } else if (letters.startsWith("yue")) {
            letters = "ve" + letters.substring(3);
        } else if (letters.startsWith("yun")) {
            letters = "un" + letters.substring(3);
        } else if (letters.startsWith("yu")) {
            letters = "v" + letters.substring(2);
        } else if (letters.startsWith("y")) {
            letters = "i" + letters.substring(1);
            if (letters.startsWith("ii")) {
                letters = "i" + letters.substring(2);
            }
            if (letters.startsWith("iou")) {
                letters = "ou" + letters.substring(3);
            }
        } else if (letters.startsWith("w")) {
            letters = "u" + letters.substring(1);
            if (letters.startsWith("uu")) {
                letters = "u" + letters.substring(2);
            }
            if (letters.startsWith("uei")) {
                letters = "ui" + letters.substring(3);
            }
            if (letters.startsWith("ueng")) {
                letters = "eng" + letters.substring(4);
            }
            if (letters.startsWith("uen")) {
                letters = "un" + letters.substring(3);
            }
        }
        // ---- 声母切分 ----
        String initial = "";
        String fin = letters;
        if (letters.length() > 2
                && (letters.startsWith("zh") || letters.startsWith("ch")
                || letters.startsWith("sh"))) {
            initial = letters.substring(0, 2);
            fin = letters.substring(2);
        } else if (letters.length() > 1
                && "bpmfdtnlgkhjqxrzcs".indexOf(letters.charAt(0)) >= 0) {
            initial = letters.substring(0, 1);
            fin = letters.substring(1);
        }
        if (fin.length() == 0) {
            return null;
        }
        // ---- 声调序：本调优先，替调兜底（个别调型 token 缺失，如 ǖ）----
        int[] order;
        if (tone >= 1 && tone <= 4) {
            order = new int[]{tone, 3, 2, 4, 1};
        } else {
            order = new int[]{1, 2, 3, 4};   // 轻声/未知：逐调试
        }
        for (int t : order) {
            String tok = markFinal(fin, t);
            if (tok != null && tokens.containsKey(tok)) {
                return initial.length() > 0 && tokens.containsKey(initial)
                        ? new String[]{initial, tok} : new String[]{tok};
            }
        }
        return null;
    }

    /**
     * 韵母字母串 + 声调 -> 带调 token。
     * 标调位置：a > o > e > 末元音(i/u/v)。v = ü。
     */
    private static String markFinal(String fin, int tone) {
        final String[] T_A = {"ā", "á", "ǎ", "à"};
        final String[] T_O = {"ō", "ó", "ǒ", "ò"};
        final String[] T_E = {"ē", "é", "ě", "è"};
        final String[] T_I = {"ī", "í", "ǐ", "ì"};
        final String[] T_U = {"ū", "ú", "ǔ", "ù"};
        final String[] T_V = {"ǖ", "ǘ", "ǚ", "ǜ"};
        // 拼写用 v 表示 ü；token 体系里 ü 与韵母连写（üè）。ü 当主元音时标调。
        if (fin.indexOf('v') >= 0) {
            fin = fin.replace('v', 'ü');
        }
        int pos;
        if (fin.indexOf('a') >= 0) {
            pos = fin.indexOf('a');
            return fin.substring(0, pos) + T_A[tone - 1] + fin.substring(pos + 1);
        }
        if (fin.indexOf('o') >= 0) {
            pos = fin.indexOf('o');
            return fin.substring(0, pos) + T_O[tone - 1] + fin.substring(pos + 1);
        }
        if (fin.indexOf('e') >= 0) {
            pos = fin.indexOf('e');
            return fin.substring(0, pos) + T_E[tone - 1] + fin.substring(pos + 1);
        }
        for (int i = fin.length() - 1; i >= 0; i--) {
            char c = fin.charAt(i);
            if (c == 'i') {
                return fin.substring(0, i) + T_I[tone - 1] + fin.substring(i + 1);
            }
            if (c == 'u') {
                return fin.substring(0, i) + T_U[tone - 1] + fin.substring(i + 1);
            }
            if (c == 'ü') {
                return fin.substring(0, i) + T_V[tone - 1] + fin.substring(i + 1);
            }
        }
        return null;
    }
}
