package com.morse.sos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/*
 * ملف واحد يحتوي التطبيق كاملاً:
 *   MainActivity   : الواجهة وربط كل الأجزاء (عامة، لأن أندرويد يشترط ذلك)
 *   MorseCodec     : الترميز وفك الترميز والتوقيت (بلا أي اعتماد على أندرويد)
 *   Crypto         : AES-256-GCM مع PBKDF2 وترميز Base32
 *   Puzzle         : الألغاز الخمسة لفتح وضع التشفير
 *   SignalSender   : توليد النغمة والإرسال بالفلاش
 *   SignalReceiver : الاستماع وكشف النغمة (Goertzel)
 * ملاحظة: الأصناف غير العامة لا تُصرَّح كملفات منفصلة، فكلها هنا.
 */
public class MainActivity extends Activity implements MainActivity.SignalListener {

    // =====================================================================
    //                              الواجهة
    // =====================================================================

    static final String[] LANG_LABELS = {"العربية", "Français", "English"};
    static final String[] LANG_CODES = {MorseCodec.AR, MorseCodec.FR, MorseCodec.EN};
    static final String[] SPEED_LABELS = {"بطيء", "عادي", "سريع"};
    static final int[] SPEED_UNITS = {200, 120, 80};
    static final int ENC_MAX = 60;
    static final int REQ_MIC = 7;

    static final int DARK = 0xFF0F172A, GREY = 0xFF64748B, BLUE = 0xFF2563EB, AMBER = 0xFFD97706,
            RED = 0xFFDC2626, SLATE = 0xFF475569, GREEN = 0xFF16A34A, PURPLE = 0xFF7C3AED;

    /** واجهة يستقبل بها التطبيق نتائج الاستماع. */
    public interface SignalListener {
        void onMorse(String morse);
        void onStatus(String text);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopFlash = new AtomicBoolean(false);
    private final SignalReceiver receiver = new SignalReceiver();
    private final Puzzle puzzle = new Puzzle();
    private final StringBuilder history = new StringBuilder();

    private float dp;
    private EditText input;
    private Spinner langSpin, speedSpin;
    private TextView preview, rxStatus, rxMorse, rxText, historyView, encBadge;
    private Button listenBtn;
    private AudioTrack track;
    private boolean listening;
    private boolean encUnlocked;
    private String passphrase = "";
    private String lastSent;
    private String lastReceived = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(p(16), p(20), p(16), p(24));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFF1F5F9);
        sv.addView(root);
        setContentView(sv);

        root.addView(header(), lpCard());
        root.addView(settingsCard(), lpCard());
        root.addView(sendCard(), lpCard());
        root.addView(receiveCard(), lpCard());
        root.addView(historyCard(), lpCard());
        root.addView(infoCard(), lpCard());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        receiver.stop();
        stopAll();
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(p(20), p(22), p(20), p(22));
        h.setBackground(grad(0xFF1E3A8A, 0xFF2563EB, 22));
        h.addView(text("مورس للطوارئ", 26, 0xFFFFFFFF, true));
        h.addView(text("أرسل واستقبل حتى بلا إنترنت، بالصوت والضوء", 14, 0xE6FFFFFF, false));
        return h;
    }

    private View settingsCard() {
        LinearLayout c = card();
        c.addView(text("الإعدادات", 18, DARK, true));
        c.addView(text("لغة الرسالة", 14, GREY, false));
        langSpin = spinner(LANG_LABELS);
        c.addView(langSpin);
        c.addView(text("السرعة (يجب أن تتطابق عند الطرفين)", 14, GREY, false));
        speedSpin = spinner(SPEED_LABELS);
        speedSpin.setSelection(1);
        c.addView(speedSpin);

        encBadge = text("🔓 التشفير: غير مفعّل", 14, GREY, false);
        c.addView(encBadge);
        c.addView(button("🔐 وضع التشفير (حلّ الألغاز)", PURPLE, v -> openEncryption()), lpFull());
        return c;
    }

    private View sendCard() {
        LinearLayout c = card();
        c.addView(text("إرسال", 18, DARK, true));

        input = new EditText(this);
        input.setHint("اكتب رسالتك (حتى " + MorseCodec.MAX_CHARS + " حرفاً، وحتى " + ENC_MAX + " مشفّرةً)");
        input.setMinLines(2);
        input.setGravity(Gravity.TOP | Gravity.START);
        c.addView(input);

        preview = text("", 13, SLATE, false);
        preview.setTypeface(Typeface.MONOSPACE);
        c.addView(preview);

        LinearLayout row = row();
        row.addView(button("🔊 بالصوت", BLUE, v -> sendAudio()), lpWeight());
        row.addView(button("🔦 بالفلاش", AMBER, v -> sendFlash()), lpWeight());
        c.addView(row);
        c.addView(button("🆘 إرسال SOS", RED, v -> sendSos()), lpFull());
        c.addView(button("🔁 تكرار آخر رسالة", GREY, v -> repeatLast()), lpFull());
        c.addView(button("⏹ إيقاف", SLATE, v -> stopAll()), lpFull());
        return c;
    }

    private View receiveCard() {
        LinearLayout c = card();
        c.addView(text("استقبال", 18, DARK, true));
        c.addView(text("ضع الهاتفين قريبين، واجعل المرسل يبدأ بعد أن تضغط ابدأ الاستماع.", 14, GREY, false));
        listenBtn = button("🎧 ابدأ الاستماع", GREEN, v -> toggleListen());
        c.addView(listenBtn, lpFull());
        rxStatus = text("متوقف", 14, SLATE, false);
        c.addView(rxStatus);
        rxMorse = text("", 13, GREY, false);
        rxMorse.setTypeface(Typeface.MONOSPACE);
        c.addView(rxMorse);
        rxText = text("", 22, DARK, true);
        c.addView(rxText);
        c.addView(button("📋 نسخ النص المستقبل", BLUE, v -> copyLast()), lpFull());
        return c;
    }

    private View historyCard() {
        LinearLayout c = card();
        c.addView(text("السجل (آخر الرسائل المستقبلة)", 16, DARK, true));
        historyView = text("لا توجد رسائل بعد.", 14, GREY, false);
        c.addView(historyView);
        c.addView(button("🗑 مسح السجل", SLATE, v -> {
            history.setLength(0);
            historyView.setText("لا توجد رسائل بعد.");
        }), lpFull());
        return c;
    }

    private View infoCard() {
        LinearLayout c = card();
        c.setBackground(grad(0xFFFFF7ED, 0xFFFFFFFF, 18));
        c.addView(text("تنبيه مهم", 18, 0xFF9A3412, true));
        c.addView(text("هذا التطبيق وسيلة مساعدة فقط، ولا يضمن الإنقاذ. الاتصال بالطوارئ الرسمية هو الأولوية دائماً.", 14, DARK, false));
        c.addView(text("الراديو الذي يخترق الركام يحتاج جهازاً مختلفاً، ولا يعمل عبر الهاتف وحده.", 14, DARK, false));
        return c;
    }

    // =====================================================================
    //                              الإرسال
    // =====================================================================

    private String lang() { return LANG_CODES[langSpin.getSelectedItemPosition()]; }
    private int unit() { return SPEED_UNITS[speedSpin.getSelectedItemPosition()]; }

    private String message() {
        String m = input.getText().toString().trim();
        if (m.isEmpty()) { toast("اكتب رسالة أولاً"); return null; }
        int max = (encUnlocked && !passphrase.isEmpty()) ? ENC_MAX : MorseCodec.MAX_CHARS;
        if (m.length() > max) { toast("الحد الأقصى " + max + " حرفاً"); return null; }
        return m;
    }

    /** يشفّر الرسالة إن كان وضع التشفير مفعّلاً، ثم يرمّزها. */
    private String morseFor(String m) throws Exception {
        if (encUnlocked && !passphrase.isEmpty()) {
            return MorseCodec.encode(Crypto.encrypt(m, passphrase), MorseCodec.ENC);
        }
        return MorseCodec.encode(m, lang());
    }

    private int[] timelineFor(String morse) {
        preview.setText(morse);
        return MorseCodec.toTimeline(morse, unit());
    }

    private void sendAudio() {
        String m = message();
        if (m == null) return;
        try {
            stopAll();
            int[] tl = timelineFor(morseFor(m));
            track = SignalSender.play(SignalSender.render(tl));
            lastSent = m;
            toast("يُرسل بالصوت...");
        } catch (Exception e) {
            toast(errorText(e));
        }
    }

    private void sendFlash() {
        String m = message();
        if (m == null) return;
        try {
            stopAll();
            runFlash(timelineFor(morseFor(m)));
            lastSent = m;
        } catch (Exception e) {
            toast(errorText(e));
        }
    }

    private void sendSos() {
        stopAll();
        int[] tl = timelineFor(MorseCodec.encode("SOS", MorseCodec.EN));
        track = SignalSender.play(SignalSender.render(tl));
        runFlash(tl);
    }

    private void repeatLast() {
        if (lastSent == null) { toast("لا توجد رسالة سابقة"); return; }
        input.setText(lastSent);
        sendAudio();
    }

    private void runFlash(final int[] tl) {
        stopFlash.set(false);
        new Thread(() -> {
            try {
                SignalSender.flash(this, tl, stopFlash);
            } catch (Exception e) {
                ui.post(() -> toast("تعذّر تشغيل الفلاش على هذا الجهاز"));
            }
        }, "morse-flash").start();
    }

    private void stopAll() {
        stopFlash.set(true);
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            track = null;
        }
    }

    // =====================================================================
    //                              الاستقبال
    // =====================================================================

    private void toggleListen() {
        if (listening) {
            receiver.stop();
            listening = false;
            listenBtn.setText("🎧 ابدأ الاستماع");
            rxStatus.setText("متوقف");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startListening();
    }

    private void startListening() {
        listening = true;
        listenBtn.setText("⏹ إيقاف الاستماع");
        receiver.start(unit(), this);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(rc, perms, grants);
        if (rc == REQ_MIC && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            toast("يحتاج التطبيق إذن الميكروفون للاستقبال");
        }
    }

    @Override
    public void onStatus(final String s) {
        ui.post(() -> rxStatus.setText(s));
    }

    @Override
    public void onMorse(final String morse) {
        ui.post(() -> {
            rxMorse.setText(morse);
            try {
                MorseCodec.Decoded d = MorseCodec.decode(morse);
                String plain;
                if (MorseCodec.ENC.equals(d.lang)) {
                    if (!encUnlocked || passphrase.isEmpty()) {
                        rxText.setText("رسالة مشفّرة: افتح وضع التشفير وأدخل كلمة السر");
                        rxStatus.setText("وصلت رسالة مشفّرة");
                        return;
                    }
                    plain = Crypto.decrypt(d.text, passphrase);
                    rxStatus.setText("تم فك التشفير");
                } else {
                    plain = d.text.isEmpty() ? "(رسالة فارغة)" : d.text;
                    rxStatus.setText("تم الاستقبال بلغة " + d.lang);
                }
                rxText.setText(plain);
                lastReceived = plain;
                addHistory(plain);
            } catch (Exception e) {
                rxText.setText(errorText(e));
            }
        });
    }

    private void addHistory(String text) {
        String time = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
        history.insert(0, time + "  " + text + "\n");
        // نُبقي على آخر 20 سطراً فقط
        String[] lines = history.toString().split("\n");
        if (lines.length > 20) {
            history.setLength(0);
            for (int i = 0; i < 20; i++) history.append(lines[i]).append('\n');
        }
        historyView.setText(history.toString().trim());
    }

    private void copyLast() {
        if (lastReceived.isEmpty()) { toast("لا يوجد نص للنسخ"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("message", lastReceived));
        toast("تم النسخ");
    }

    // =====================================================================
    //                          وضع التشفير والألغاز
    // =====================================================================

    private void openEncryption() {
        if (encUnlocked) { askPassphrase(); return; }
        final EditText ans = new EditText(this);
        ans.setHint("إجابتك");
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("الألغاز (" + puzzle.solvedCount() + "/" + puzzle.total() + ")")
                .setMessage(puzzle.currentQuestion())
                .setView(ans)
                .setPositiveButton("تحقق", null)
                .setNegativeButton("إغلاق", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!puzzle.submit(ans.getText().toString())) {
                toast("إجابة غير صحيحة، حاول مجدداً");
                return;
            }
            dlg.dismiss();
            if (puzzle.isUnlocked()) {
                encUnlocked = true;
                encBadge.setText("🔒 التشفير: مفعّل (AES-256-GCM)");
                toast("تم فتح وضع التشفير");
                askPassphrase();
            } else {
                openEncryption();
            }
        }));
        dlg.show();
    }

    private void askPassphrase() {
        final EditText pw = new EditText(this);
        pw.setHint("كلمة السر المشتركة مع الطرف الآخر");
        pw.setText(passphrase);
        new AlertDialog.Builder(this)
                .setTitle("كلمة السر")
                .setMessage("لا تُرسل كلمة السر عبر التطبيق. اتفق عليها مع الطرف الآخر بطريقة آمنة.")
                .setView(pw)
                .setPositiveButton("حفظ", (d, w) -> {
                    passphrase = pw.getText().toString();
                    toast(passphrase.isEmpty() ? "لم تُحفظ كلمة السر" : "تم حفظ كلمة السر");
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // =====================================================================
    //                              أدوات
    // =====================================================================

    private static String errorText(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private int p(int v) { return (int) (v * dp); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(p(16), p(14), p(16), p(14));
        c.setBackground(grad(0xFFFFFFFF, 0xFFFFFFFF, 18));
        c.setElevation(p(3));
        return c;
    }

    private GradientDrawable grad(int c1, int c2, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(p(radius));
        return g;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, p(4), 0, p(4));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(p(12));
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        return s;
    }

    private LinearLayout.LayoutParams lpCard() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = p(12);
        return lp;
    }

    private LinearLayout.LayoutParams lpFull() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = p(8);
        return lp;
    }

    private LinearLayout.LayoutParams lpWeight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(p(4), p(8), p(4), 0);
        return lp;
    }
}

// =========================================================================
//                    MorseCodec: الترميز (وحدة مستقلة)
// =========================================================================

final class MorseCodec {

    static final String AR = "AR", FR = "FR", EN = "EN";
    /** رمز لغة الرسائل المشفّرة. الحمولة بعده Base32 وليست نصاً. */
    static final String ENC = "XX";
    static final int MAX_CHARS = 200;

    private static final Map<Character, String> LATIN = new HashMap<>();
    private static final Map<String, Character> LATIN_REV = new HashMap<>();
    private static final Map<Character, String> ARABIC = new HashMap<>();
    private static final Map<String, Character> ARABIC_REV = new HashMap<>();

    static {
        String[] latin = {
            "A.-", "B-...", "C-.-.", "D-..", "E.", "F..-.", "G--.", "H....", "I..", "J.---",
            "K-.-", "L.-..", "M--", "N-.", "O---", "P.--.", "Q--.-", "R.-.", "S...", "T-",
            "U..-", "V...-", "W.--", "X-..-", "Y-.--", "Z--..",
            "0-----", "1.----", "2..---", "3...--", "4....-", "5.....", "6-....", "7--...", "8---..", "9----.",
            "..-.-.-", ",--..--", "?..--..", "!-.-.--", "/-..-.", "(-.--.", ")-.--.-", "&.-...",
            ":---...", ";-.-.-.", "=-...-", "+.-.-.", "--....-", "@.--.-.", "'.----.", "\".-..-."
        };
        for (String s : latin) {
            char k = s.charAt(0);
            String c = s.substring(1);
            LATIN.put(k, c);
            LATIN_REV.put(c, k);
        }
        // جدول عربي داخلي: الحروف الأكثر تكراراً تحصل على أقصر الشيفرات.
        String arOrder = "اليونمربتهعكسقفدحجطصضشزذثخظغءة";
        List<String> pats = new ArrayList<>();
        for (int len = 1; len <= 4; len++) {
            for (int i = 0; i < (1 << len); i++) {
                StringBuilder b = new StringBuilder();
                for (int j = len - 1; j >= 0; j--) b.append(((i >> j) & 1) == 1 ? '-' : '.');
                pats.add(b.toString());
            }
        }
        for (int i = 0; i < arOrder.length(); i++) {
            ARABIC.put(arOrder.charAt(i), pats.get(i));
            ARABIC_REV.put(pats.get(i), arOrder.charAt(i));
        }
    }

    private MorseCodec() {}

    private static String normalizeLatin(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeArabic(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("\u0640", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                .replace('ى', 'ي').replace('ؤ', 'ء').replace('ئ', 'ء');
    }

    static String encode(String text, String lang) {
        boolean ar = AR.equals(lang);
        StringBuilder out = new StringBuilder();
        out.append(LATIN.get(lang.charAt(0))).append(' ').append(LATIN.get(lang.charAt(1)));
        String norm = ar ? normalizeArabic(text) : normalizeLatin(text);
        for (String word : norm.trim().split("\\s+")) {
            StringBuilder w = new StringBuilder();
            for (char c : word.toCharArray()) {
                String code = ar ? ARABIC.get(c) : LATIN.get(c);
                if (code == null) continue;
                if (w.length() > 0) w.append(' ');
                w.append(code);
            }
            if (w.length() > 0) out.append(" / ").append(w);
        }
        return out.toString();
    }

    static final class Decoded {
        final String lang;
        final String text;
        Decoded(String lang, String text) { this.lang = lang; this.text = text; }
    }

    static Decoded decode(String morse) {
        String[] words = morse.trim().split("\\s*/\\s*");
        if (words.length == 0 || words[0].isEmpty()) throw new IllegalArgumentException("الرسالة فارغة");
        StringBuilder langSb = new StringBuilder();
        for (String code : words[0].split(" +")) {
            Character c = LATIN_REV.get(code);
            langSb.append(c == null ? '?' : c);
        }
        String lang = langSb.toString();
        if (!AR.equals(lang) && !FR.equals(lang) && !EN.equals(lang) && !ENC.equals(lang)) {
            throw new IllegalArgumentException("لغة غير معروفة");
        }
        Map<String, Character> rev = AR.equals(lang) ? ARABIC_REV : LATIN_REV;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < words.length; i++) {
            if (i > 1) sb.append(' ');
            for (String code : words[i].trim().split(" +")) {
                Character c = rev.get(code);
                sb.append(c == null ? '?' : c);
            }
        }
        return new Decoded(lang, sb.toString());
    }

    /** مدد متناوبة بالمللي ثانية: [تشغيل، إطفاء، ...]. النقطة وحدة، الشرطة 3، فاصل الحرف 3، فاصل الكلمة 7. */
    static int[] toTimeline(String morse, int unit) {
        List<Integer> out = new ArrayList<>();
        int gap = 0;
        boolean sawSymbol = false;
        for (char c : morse.toCharArray()) {
            if (c == '.' || c == '-') {
                if (sawSymbol) out.add(gap);
                out.add(c == '.' ? unit : 3 * unit);
                gap = unit;
                sawSymbol = true;
            } else if (c == ' ') {
                if (gap == unit) gap = 3 * unit;
            } else if (c == '/') {
                gap = 7 * unit;
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }
}

// =========================================================================
//                    Crypto: AES-256-GCM + PBKDF2 + Base32
// =========================================================================

final class Crypto {

    private static final int VERSION = 1;
    private static final int SALT = 16, IV = 12, TAG_BITS = 128, TAG_BYTES = 16;
    private static final int ITERATIONS = 150_000, KEY_BITS = 256;
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {}

    /** [إصدار][ملح][IV][نص مشفّر + وسم] ثم Base32. */
    static String encrypt(String plain, String passphrase) throws Exception {
        byte[] salt = new byte[SALT], iv = new byte[IV];
        RNG.nextBytes(salt);
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[1 + SALT + IV + ct.length];
        out[0] = VERSION;
        System.arraycopy(salt, 0, out, 1, SALT);
        System.arraycopy(iv, 0, out, 1 + SALT, IV);
        System.arraycopy(ct, 0, out, 1 + SALT + IV, ct.length);
        return base32(out);
    }

    static String decrypt(String payload, String passphrase) throws Exception {
        byte[] in = unbase32(payload);
        if (in.length < 1 + SALT + IV + TAG_BYTES) throw new IllegalArgumentException("الرسالة قصيرة أو تالفة");
        if (in[0] != VERSION) throw new IllegalArgumentException("إصدار تشفير غير مدعوم");
        byte[] salt = Arrays.copyOfRange(in, 1, 1 + SALT);
        byte[] iv = Arrays.copyOfRange(in, 1 + SALT, 1 + SALT + IV);
        byte[] ct = Arrays.copyOfRange(in, 1 + SALT + IV, in.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
        try {
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("كلمة السر غير صحيحة، أو الرسالة تعرّضت للتعديل");
        }
    }

    private static SecretKeySpec deriveKey(String passphrase, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    private static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf = ((buf << 8) | (b & 0xFF)) & 0xFFFF;
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buf >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    private static byte[] unbase32(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char ch : s.toCharArray()) {
            int v = B32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("رموز غير صالحة في الرسالة");
            buf = ((buf << 5) | v) & 0xFFFF;
            bits += 5;
            if (bits >= 8) {
                o.write((buf >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return o.toByteArray();
    }
}

// =========================================================================
//                    Puzzle: الألغاز الخمسة
// =========================================================================

final class Puzzle {

    static final String[] QUESTIONS = {
        "١. أنا لا أتكلم، لكنني أجيب دائماً كلما ناديت. فما أنا؟",
        "٢. كلما أخذت مني ازددت. فما أنا؟",
        "٣. لي مدن بلا بيوت، وجبال بلا حجارة، وماء بلا سمك. فما أنا؟",
        "٤. أمشي بلا أرجل، وأدخل بلا باب، وأفتح الأبواب المغلقة. فما أنا؟",
        "٥. ما الشيء الذي يُكسر ولا يُرى ولا يُلمس؟"
    };

    // بصمات SHA-256 لإجابات الأسئلة بعد التطبيع (لا نص واضح في الكود)
    private static final String[] HASHES = {
        "f358f7e00fffc9be3462bc5c6874327379b8951b97ea84fbf03c33df49daa4bd",
        "80af9e4eadd004cbe0b0c2299e921c3d89c8bbd8a9028fc09f961b22dcbb41e3",
        "d1d8e58855642508eabae0d2f91a570be08c79a70c2306b635869e2e48032b16",
        "1db8f04bac958ea56df956b8523419c2d35325c1f862b333f93581e8ed1ab7de",
        "90c54ba3a1f8dbe59efb232936687157c828523ea4e7ae714c084241e6c53d18"
    };

    private int solved = 0;

    int solvedCount() { return solved; }
    int total() { return QUESTIONS.length; }
    boolean isUnlocked() { return solved >= QUESTIONS.length; }
    String currentQuestion() { return isUnlocked() ? null : QUESTIONS[solved]; }

    boolean submit(String answer) {
        if (isUnlocked()) return true;
        if (sha256(normalize(answer)).equals(HASHES[solved])) {
            solved++;
            return true;
        }
        return false;
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه');
        return n.trim().replaceAll("\\s+", " ");
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

// =========================================================================
//                    SignalSender: توليد النغمة والفلاش
// =========================================================================

final class SignalSender {

    static final int RATE = 8000, TONE = 700, FADE = 40;

    /** [تشغيل، إطفاء، ...] إلى PCM. */
    static short[] render(int[] timeline) {
        int total = 0;
        for (int d : timeline) total += d * RATE / 1000;
        short[] pcm = new short[total];
        int pos = 0;
        for (int i = 0; i < timeline.length; i++) {
            int n = timeline[i] * RATE / 1000;
            if (i % 2 == 0) {
                for (int k = 0; k < n; k++) {
                    double env = Math.min(1.0, Math.min(k, n - 1 - k) / (double) FADE);
                    pcm[pos + k] = (short) (Math.sin(2 * Math.PI * TONE * (pos + k) / RATE) * 12000 * env);
                }
            }
            pos += n;
        }
        return pcm;
    }

    static AudioTrack play(short[] pcm) {
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        t.play();
        return t;
    }

    static void flash(Context ctx, int[] timeline, AtomicBoolean stop) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        String id = null;
        for (String s : cm.getCameraIdList()) {
            Boolean has = cm.getCameraCharacteristics(s).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(has)) { id = s; break; }
        }
        if (id == null) throw new IllegalStateException("لا يوجد فلاش في هذا الجهاز");
        try {
            for (int i = 0; i < timeline.length && !stop.get(); i++) {
                cm.setTorchMode(id, i % 2 == 0);
                Thread.sleep(timeline[i]);
            }
        } finally {
            cm.setTorchMode(id, false);
        }
    }
}

// =========================================================================
//                    SignalReceiver: كشف النغمة (Goertzel)
// =========================================================================

final class SignalReceiver {

    private static final int RATE = 8000, BLOCK = 80, K = 7; // K*RATE/BLOCK = 700 هرتز
    private volatile boolean running;

    void start(final int unit, final MainActivity.SignalListener l) {
        if (running) return;
        running = true;
        new Thread(() -> loop(unit, l), "morse-rx").start();
    }

    void stop() { running = false; }

    private void loop(int unit, MainActivity.SignalListener l) {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, BLOCK * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                l.onStatus("تعذّر فتح الميكروفون");
                return;
            }
            rec.startRecording();
            l.onStatus("جارٍ الاستماع...");

            short[] buf = new short[BLOCK];
            double noise = 1e6;
            boolean on = false, emitted = false;
            int run = 0;
            StringBuilder sym = new StringBuilder();

            while (running) {
                int n = rec.read(buf, 0, BLOCK);
                if (n <= 0) continue;
                double p = goertzel(buf);
                noise = p < noise ? p : noise + (p - noise) * 0.02;
                boolean tone = p > Math.max(noise * 10, 1e8);

                if (tone == on) {
                    run++;
                } else {
                    int ms = run * 10;
                    if (on) {
                        sym.append(ms < 2 * unit ? '.' : '-');
                    } else if (ms >= 5 * unit) {
                        sym.append(" / ");
                    } else if (ms >= 2 * unit) {
                        sym.append(' ');
                    }
                    on = tone;
                    run = 1;
                    if (on) emitted = false;
                }

                if (!on && !emitted && run * 10 >= 10 * unit && sym.length() > 0) {
                    String msg = sym.toString().trim().replaceAll("(\\s*/\\s*)+$", "");
                    sym.setLength(0);
                    emitted = true;
                    l.onMorse(msg);
                    l.onStatus("جارٍ الاستماع...");
                }
            }
        } catch (Exception e) {
            l.onStatus("خطأ في الاستقبال: " + e.getMessage());
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
        }
    }

    private static double goertzel(short[] x) {
        double w = 2 * Math.PI * K / BLOCK;
        double c = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (short v : x) {
            double s0 = v + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2;
    }
}
