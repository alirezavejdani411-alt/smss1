package com.seveneast.smscontroller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 7East ESP32 + SIM800 SMS controller UI.
 * Labels are stored only on this phone. Live status changes only after an SMS response.
 */
public class MainActivity extends Activity {
    private static final int NUM_COILS = 40;
    private static final int NUM_REGS = 20;
    private static final int REQUEST_SMS_PERMISSIONS = 710;
    private static final long RESPONSE_TIMEOUT_MS = 90_000L;
    private static final String ACTION_INCOMING = SmsReceiver.ACTION_INCOMING;
    private static final String ACTION_SENT_RESULT = SmsSentReceiver.ACTION_SENT_RESULT;

    private static final int BG = Color.rgb(11, 17, 32);
    private static final int CARD = Color.rgb(24, 35, 56);
    private static final int CARD_DARK = Color.rgb(15, 25, 43);
    private static final int BORDER = Color.rgb(47, 65, 91);
    private static final int TEXT = Color.rgb(235, 242, 252);
    private static final int MUTED = Color.rgb(157, 174, 197);
    private static final int BLUE = Color.rgb(14, 165, 233);
    private static final int GREEN = Color.rgb(16, 185, 129);
    private static final int RED = Color.rgb(239, 68, 68);
    private static final int AMBER = Color.rgb(245, 158, 11);
    private static final int PURPLE = Color.rgb(139, 92, 246);

    private SharedPreferences prefs;
    private SharedPreferences smsStore;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // -1 = unknown; 0 = OFF; 1 = ON; 2 = PULSE
    private final int[] coilState = new int[NUM_COILS];
    private final int[] regValue = new int[NUM_REGS];
    private final boolean[] regKnown = new boolean[NUM_REGS];
    private String[] coilNames = new String[NUM_COILS];
    private String[] regNames = new String[NUM_REGS];

    private int currentTab = 0;
    private String stateMessage = "برای دریافت وضعیت، دکمهٔ رفرش را بزنید.";
    private String lastReply = "هنوز پاسخی دریافت نشده است.";
    private long lastReplyAt = 0L;

    private boolean waiting = false;
    private String expectedType = "";
    private int expectedIndex = -1;
    private String pendingCommand = "";
    private long activeToken = 0L;
    private long tokenCounter = System.currentTimeMillis();
    private boolean adminNeedsNumber = false;
    private String adminFollowupCommand = "";

    private TextView waitView;
    private TextView stateView;
    private EditText deviceNumberInput;
    private EditText adminTargetInput;
    private EditText slaveIdInput;
    private LinearLayout contentHost;
    private BroadcastReceiver internalReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 12, 24));
        getWindow().setNavigationBarColor(Color.rgb(7, 12, 24));
        prefs = getSharedPreferences("local_settings", MODE_PRIVATE);
        smsStore = getSharedPreferences(SmsReceiver.STORE, MODE_PRIVATE);
        java.util.Arrays.fill(coilState, -1);
        loadNames();
        adminNeedsNumber = prefs.getBoolean("admin_needs_number", false);
        adminFollowupCommand = prefs.getString("admin_followup_command", "");
        lastReply = prefs.getString("last_reply", lastReply);
        lastReplyAt = prefs.getLong("last_reply_at", 0L);
        render();
        ensureSmsPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_INCOMING);
        filter.addAction(ACTION_SENT_RESULT);
        internalReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (ACTION_INCOMING.equals(intent.getAction())) {
                    handleIncomingOnce(intent.getStringExtra("sender"), intent.getStringExtra("body"), intent.getLongExtra("id", 0L));
                } else if (ACTION_SENT_RESULT.equals(intent.getAction())) {
                    long token = intent.getLongExtra("token", -1L);
                    boolean success = intent.getBooleanExtra("success", false);
                    if (waiting && token == activeToken && !success) {
                        String reason = "ارسال پیامک توسط اندروید تأیید نشد؛ شبکه یا سیم‌کارت را بررسی کنید.";
                        finishTransaction(reason);
                    }
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(internalReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(internalReceiver, filter);
        processStoredSmsIfNew();
    }

    @Override
    protected void onStop() {
        if (internalReceiver != null) {
            try { unregisterReceiver(internalReceiver); } catch (IllegalArgumentException ignored) { }
            internalReceiver = null;
        }
        super.onStop();
    }

    private void ensureSmsPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        boolean send = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean receive = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        if (!send || !receive) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS}, REQUEST_SMS_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SMS_PERMISSIONS) {
            boolean ok = true;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) ok = false;
            stateMessage = ok ? "مجوز پیامک فعال شد." : "برای ارسال و دریافت خودکار پیامک، مجوزهای SMS را فعال کنید.";
            render();
        }
    }

    private boolean hasSmsPermissions() {
        return Build.VERSION.SDK_INT < 23 ||
                (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED);
    }

    private void loadNames() {
        for (int i = 0; i < NUM_COILS; i++) {
            coilNames[i] = prefs.getString("coil_name_" + i, "رله " + (i + 1));
        }
        for (int i = 0; i < NUM_REGS; i++) {
            regNames[i] = prefs.getString("reg_name_" + i, "رجیستر " + (i + 1));
        }
    }

    private String deviceNumber() {
        return prefs.getString("device_number", "").trim();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(24));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(16), dp(16), dp(16));
        header.setBackground(round(CARD, 18, BORDER));
        TextView title = text("⚡ 7East  |  کنترل پیامکی", 21, TEXT, true);
        header.addView(title);
        TextView subtitle = text("ESP32 · SIM800 · Modbus", 12, MUTED, false);
        subtitle.setPadding(0, dp(5), 0, 0);
        header.addView(subtitle);
        root.addView(header, lpMatchWrap(0, 0, 0, 12));

        addDeviceNumberCard(root);

        LinearLayout statusCard = card();
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        waitView = text(waiting ? "⏳ منتظر پاسخ..." : "● آماده", 13, waiting ? AMBER : GREEN, true);
        statusRow.addView(waitView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = button(waiting ? "در انتظار پاسخ" : "↻ رفرش وضعیت", BLUE, Color.WHITE);
        refresh.setEnabled(!waiting);
        refresh.setOnClickListener(v -> refreshAll());
        statusRow.addView(refresh);
        statusCard.addView(statusRow);
        stateView = text(stateMessage, 13, MUTED, false);
        stateView.setPadding(0, dp(10), 0, 0);
        statusCard.addView(stateView);
        root.addView(statusCard, lpMatchWrap(0, 0, 0, 12));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(round(CARD_DARK, 14, BORDER));
        String[] labels = {"🎛 رله‌ها", "📊 رجیسترها", "🛡 مدیریت"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            Button b = button(labels[i], currentTab == i ? BLUE : CARD_DARK, currentTab == i ? Color.WHITE : MUTED);
            b.setTextSize(12);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(44), 1);
            tp.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(b, tp);
            b.setOnClickListener(v -> { currentTab = tab; render(); });
        }
        root.addView(tabs, lpMatchWrap(0, 0, 0, 12));

        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(contentHost, lpMatchWrap(0, 0, 0, 12));
        if (currentTab == 0) renderRelays();
        else if (currentTab == 1) renderRegisters();
        else renderAdmin();

        LinearLayout responseCard = card();
        TextView responseTitle = text("آخرین پیام برگشتی از کنترلر", 14, TEXT, true);
        responseCard.addView(responseTitle);
        TextView response = text(lastReply, 13, TEXT, false);
        response.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
        response.setGravity(Gravity.START);
        response.setPadding(0, dp(9), 0, 0);
        responseCard.addView(response);
        if (lastReplyAt > 0) {
            TextView at = text("دریافت: " + new SimpleDateFormat("HH:mm:ss  yyyy/MM/dd", Locale.getDefault()).format(new Date(lastReplyAt)), 11, MUTED, false);
            at.setPadding(0, dp(6), 0, 0);
            responseCard.addView(at);
        }
        root.addView(responseCard, lpMatchWrap(0, 0, 0, 8));

        TextView footer = text("نام‌گذاری‌ها فقط روی همین گوشی ذخیره می‌شوند. وضعیت‌ها با دریافت پیامک به‌روزرسانی می‌شوند.", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(8), dp(7), dp(8), dp(7));
        root.addView(footer);
        setContentView(scroll);
    }

    private void addDeviceNumberCard(LinearLayout root) {
        LinearLayout c = card();
        c.addView(text("شمارهٔ سیم‌کارت داخل کنترلر", 14, TEXT, true));
        c.addView(text("پاسخ‌های پیامکی از این شماره تشخیص داده می‌شوند. شماره باید همان شمارهٔ سیم‌کارت SIM800 باشد.", 11, MUTED, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        deviceNumberInput = new EditText(this);
        deviceNumberInput.setSingleLine(true);
        deviceNumberInput.setTextSize(15);
        deviceNumberInput.setTextColor(TEXT);
        deviceNumberInput.setHintTextColor(MUTED);
        deviceNumberInput.setHint("مثلاً 0912xxxxxxx");
        deviceNumberInput.setInputType(InputType.TYPE_CLASS_PHONE);
        deviceNumberInput.setText(deviceNumber());
        deviceNumberInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        deviceNumberInput.setBackground(round(CARD_DARK, 10, BORDER));
        deviceNumberInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                prefs.edit().putString("device_number", s.toString().trim()).apply();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        row.addView(deviceNumberInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button save = button("ذخیره", GREEN, Color.WHITE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        sp.setMargins(dp(7), 0, 0, 0);
        row.addView(save, sp);
        save.setOnClickListener(v -> {
            String normalized = normalizePhone(deviceNumberInput.getText().toString());
            if (normalized.length() < 10) {
                toast("شمارهٔ سیم‌کارت کنترلر را کامل وارد کنید.");
            } else {
                prefs.edit().putString("device_number", deviceNumberInput.getText().toString().trim()).apply();
                stateMessage = "شمارهٔ کنترلر ذخیره شد.";
                toast("شماره ذخیره شد.");
                render();
            }
        });
        c.addView(row, lpMatchWrap(0, 0, 0, 0));
        root.addView(c, lpMatchWrap(0, 0, 0, 12));
    }

    private void renderRelays() {
        contentHost.addView(sectionTitle("وضعیت رله‌ها", "۴۰ کانال"));
        LinearLayout info = card();
        info.addView(text("برای ایمنی، تا قبل از دریافت STATUS وضعیت رله‌ها نامشخص است. در وضعیت نامشخص، لمس دکمه فقط رفرش می‌کند و رله را تغییر نمی‌دهد.", 12, MUTED, false));
        contentHost.addView(info, lpMatchWrap(0, 0, 0, 10));

        for (int start = 0; start < NUM_COILS; start += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.addView(relayCard(start), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (start + 1 < NUM_COILS) {
                View gap = new View(this);
                row.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
                row.addView(relayCard(start + 1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            contentHost.addView(row, lpMatchWrap(0, 0, 0, 8));
        }
    }

    private View relayCard(int index) {
        LinearLayout c = card();
        int state = coilState[index];
        int stateColor = state == 1 ? GREEN : (state == 0 ? MUTED : AMBER);
        int tileColor = state == 1 ? Color.rgb(17, 58, 54) : (state == 0 ? CARD : (state == 2 ? Color.rgb(68, 48, 22) : CARD_DARK));
        c.setBackground(round(tileColor, 15, state == 1 ? Color.rgb(16, 185, 129) : (state == 2 ? AMBER : BORDER)));
        TextView number = text("RELAY " + (index + 1), 10, MUTED, true);
        c.addView(number);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(coilNames[index], 14, TEXT, true);
        name.setMaxLines(2);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button rename = button("✎", CARD_DARK, MUTED);
        rename.setTextSize(12);
        nameRow.addView(rename, new LinearLayout.LayoutParams(dp(36), dp(34)));
        rename.setOnClickListener(v -> editNameDialog(true, index));
        c.addView(nameRow, lpMatchWrap(0, 5, 0, 6));

        String label = state == 1 ? "● روشن" : state == 0 ? "○ خاموش" : state == 2 ? "⚡ پالس" : "? نامشخص";
        TextView status = text(label, 13, stateColor, true);
        status.setPadding(0, dp(4), 0, dp(9));
        c.addView(status);
        Button action;
        if (state == -1) action = button("↻ رفرش", CARD_DARK, TEXT);
        else if (state == 1) action = button("خاموش کن", RED, Color.WHITE);
        else if (state == 2) action = button("اجرای پالس", AMBER, Color.WHITE);
        else action = button("روشن کن", GREEN, Color.WHITE);
        action.setEnabled(!waiting);
        action.setOnClickListener(v -> {
            if (waiting) return;
            if (coilState[index] == -1) refreshAll();
            else sendCommand(String.valueOf(index + 1), "RELAY", index, false);
        });
        c.addView(action, lpMatchWrap(0, 0, 0, 0));
        c.setOnLongClickListener(v -> { editNameDialog(true, index); return true; });
        return c;
    }

    private void renderRegisters() {
        contentHost.addView(sectionTitle("رجیسترها", "۲۰ مقدار"));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("↻ دریافت VARS", BLUE, Color.WHITE);
        refresh.setEnabled(!waiting);
        refresh.setOnClickListener(v -> sendCommand("VARS", "VARS", -1, false));
        Button all = button("↻ رفرش همه", CARD_DARK, TEXT);
        all.setEnabled(!waiting);
        all.setOnClickListener(v -> refreshAll());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(45), 1));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        actions.addView(all, new LinearLayout.LayoutParams(0, dp(45), 1));
        contentHost.addView(actions, lpMatchWrap(0, 0, 0, 10));

        for (int start = 0; start < NUM_REGS; start += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(registerCard(start), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (start + 1 < NUM_REGS) {
                row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
                row.addView(registerCard(start + 1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            contentHost.addView(row, lpMatchWrap(0, 0, 0, 8));
        }
    }

    private View registerCard(int index) {
        LinearLayout c = card();
        c.addView(text("REGISTER " + (index + 1), 10, MUTED, true));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(regNames[index], 13, TEXT, true);
        title.setMaxLines(2);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button rename = button("✎", CARD_DARK, MUTED);
        rename.setTextSize(12);
        titleRow.addView(rename, new LinearLayout.LayoutParams(dp(34), dp(32)));
        rename.setOnClickListener(v -> editNameDialog(false, index));
        c.addView(titleRow, lpMatchWrap(0, 5, 0, 6));
        TextView val = text(regKnown[index] ? String.valueOf(regValue[index]) : "—", 21, regKnown[index] ? BLUE : AMBER, true);
        val.setPadding(0, dp(2), 0, dp(8));
        c.addView(val);
        Button edit = button("✎ نوشتن مقدار", PURPLE, Color.WHITE);
        edit.setEnabled(!waiting);
        edit.setOnClickListener(v -> editRegisterValue(index));
        c.addView(edit, lpMatchWrap(0, 0, 0, 0));
        return c;
    }

    private void renderAdmin() {
        contentHost.addView(sectionTitle("دستورات مدیر", "طبق سطح دسترسی سیم‌کارت"));
        LinearLayout caution = card();
        caution.addView(text("این فرمان‌ها فقط در صورتی اجرا می‌شوند که شمارهٔ این گوشی در کنترلر به‌عنوان مدیر ثبت شده باشد. فرمان‌ها دقیقاً مطابق فریمور فعلی ارسال می‌شوند.", 12, MUTED, false));
        contentHost.addView(caution, lpMatchWrap(0, 0, 0, 10));

        LinearLayout pulseCard = card();
        pulseCard.addView(text("اجرای پالس روی رله", 14, TEXT, true));
        pulseCard.addView(text("فرمان PULSE فقط برای کانال‌هایی پذیرفته می‌شود که در فریمور Latch نباشند.", 11, MUTED, false));
        LinearLayout pulseRow = new LinearLayout(this);
        pulseRow.setOrientation(LinearLayout.HORIZONTAL);
        pulseRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText pulseInput = new EditText(this);
        pulseInput.setSingleLine(true);
        pulseInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pulseInput.setHint("شمارهٔ رله 1 تا 40");
        pulseInput.setTextColor(TEXT);
        pulseInput.setHintTextColor(MUTED);
        pulseInput.setBackground(round(CARD_DARK, 10, BORDER));
        pulseInput.setPadding(dp(12), dp(5), dp(12), dp(5));
        pulseRow.addView(pulseInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button pulseButton = button("⚡ اجرای PULSE", AMBER, Color.WHITE);
        pulseButton.setEnabled(!waiting);
        LinearLayout.LayoutParams pulseBp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        pulseBp.setMargins(dp(8), 0, 0, 0);
        pulseRow.addView(pulseButton, pulseBp);
        pulseButton.setOnClickListener(v -> {
            int number = parseInt(pulseInput.getText().toString(), -1);
            if (number < 1 || number > NUM_COILS) { toast("شمارهٔ رله باید بین 1 و 40 باشد."); return; }
            sendCommand("PULSE " + number, "PULSE_CMD", number - 1, false);
        });
        pulseCard.addView(pulseRow, lpMatchWrap(0, 8, 0, 0));
        contentHost.addView(pulseCard, lpMatchWrap(0, 0, 0, 10));

        addAdminCommandButton("📋 LIST — فهرست فرمان‌ها", "LIST");
        addAdminCommandButton("ℹ ABOUT — راهنما", "ABOUT");
        addAdminCommandButton("🔑 ADOTP — ساخت کد دعوت مدیر", "ADOTP");
        addAdminCommandButton("🔑 USOTP — ساخت کد دعوت کاربر", "USOTP");
        addAdminCommandButton("➕ ADUS — افزودن کاربر", "ADUS");
        addAdminCommandButton("➖ DELUS — حذف کاربر", "DELUS");
        addAdminCommandButton("➕ ADAD — افزودن مدیر", "ADAD");
        addAdminCommandButton("➖ DELAD — حذف مدیر", "DELAD");

        LinearLayout idCard = card();
        idCard.addView(text("تغییر Modbus Slave ID", 14, TEXT, true));
        idCard.addView(text("محدودهٔ مجاز: 1 تا 247", 11, MUTED, false));
        LinearLayout idRow = new LinearLayout(this);
        idRow.setOrientation(LinearLayout.HORIZONTAL);
        idRow.setGravity(Gravity.CENTER_VERTICAL);
        slaveIdInput = new EditText(this);
        slaveIdInput.setSingleLine(true);
        slaveIdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        slaveIdInput.setText(prefs.getString("last_slave_id", "1"));
        slaveIdInput.setTextColor(TEXT);
        slaveIdInput.setHintTextColor(MUTED);
        slaveIdInput.setBackground(round(CARD_DARK, 10, BORDER));
        slaveIdInput.setPadding(dp(12), dp(3), dp(12), dp(3));
        idRow.addView(slaveIdInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button setId = button("ارسال SETID", PURPLE, Color.WHITE);
        setId.setEnabled(!waiting);
        LinearLayout.LayoutParams sidp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        sidp.setMargins(dp(8), 0, 0, 0);
        idRow.addView(setId, sidp);
        setId.setOnClickListener(v -> {
            String raw = slaveIdInput.getText().toString().trim();
            int id;
            try { id = Integer.parseInt(raw); } catch (NumberFormatException ex) { id = -1; }
            if (id < 1 || id > 247) { toast("Slave ID باید بین 1 تا 247 باشد."); return; }
            prefs.edit().putString("last_slave_id", String.valueOf(id)).apply();
            sendCommand("SETID " + id, "ADMIN", -1, false);
        });
        idCard.addView(idRow, lpMatchWrap(0, 8, 0, 0));
        contentHost.addView(idCard, lpMatchWrap(0, 0, 0, 10));

        if (adminNeedsNumber) {
            LinearLayout targetCard = card();
            targetCard.addView(text("تکمیل فرمان مدیر: " + adminFollowupCommand, 14, AMBER, true));
            targetCard.addView(text("شمارهٔ مقصد را وارد کنید. برنامه آن را در قالب دقیق *شماره* ارسال می‌کند.", 12, MUTED, false));
            adminTargetInput = new EditText(this);
            adminTargetInput.setSingleLine(true);
            adminTargetInput.setInputType(InputType.TYPE_CLASS_PHONE);
            adminTargetInput.setHint("مثلاً 0912xxxxxxx");
            adminTargetInput.setTextColor(TEXT);
            adminTargetInput.setHintTextColor(MUTED);
            adminTargetInput.setBackground(round(CARD_DARK, 10, BORDER));
            adminTargetInput.setPadding(dp(12), dp(5), dp(12), dp(5));
            targetCard.addView(adminTargetInput, lpMatchWrap(0, 8, 0, 8));
            Button submit = button("ارسال شمارهٔ کاربر/مدیر", GREEN, Color.WHITE);
            submit.setEnabled(!waiting);
            submit.setOnClickListener(v -> submitAdminTarget());
            targetCard.addView(submit, lpMatchWrap(0, 0, 0, 0));
            contentHost.addView(targetCard, 0, lpMatchWrap(0, 0, 0, 10));
        }
    }

    private void addAdminCommandButton(String label, String command) {
        Button b = button(label, CARD, TEXT);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        b.setTextSize(13);
        b.setEnabled(!waiting && !adminNeedsNumber);
        b.setOnClickListener(v -> sendCommand(command, "ADMIN", -1, false));
        contentHost.addView(b, lpMatchWrap(0, 0, 0, 7));
    }

    private void submitAdminTarget() {
        if (waiting || !adminNeedsNumber || adminTargetInput == null) return;
        String phone = normalizePhone(adminTargetInput.getText().toString());
        if (phone.length() < 10 || phone.length() > 15) {
            toast("شماره را کامل و صحیح وارد کنید.");
            return;
        }
        String command = "*" + phone + "*";
        prefs.edit().putBoolean("admin_needs_number", false).putString("admin_followup_command", "").apply();
        adminNeedsNumber = false;
        String previous = adminFollowupCommand;
        adminFollowupCommand = "";
        currentTab = 2;
        sendCommand(command, "ADMIN_NUM", -1, false);
        stateMessage = "شماره برای تکمیل فرمان " + previous + " ارسال شد؛ منتظر پاسخ هستیم.";
        render();
    }

    private void refreshAll() {
        if (waiting) return;
        java.util.Arrays.fill(coilState, -1);
        for (int i = 0; i < NUM_REGS; i++) regKnown[i] = false;
        sendCommand("STATUS", "STATUS", -1, true);
    }

    private void editNameDialog(boolean coil, int index) {
        EditText input = new EditText(this);
        input.setText(coil ? coilNames[index] : regNames[index]);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(12), dp(7), dp(12), dp(7));
        input.setBackground(round(CARD_DARK, 10, BORDER));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(4), dp(20), dp(4));
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        new AlertDialog.Builder(this)
                .setTitle(coil ? "نام رلهٔ " + (index + 1) : "نام رجیستر " + (index + 1))
                .setView(wrap)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) value = coil ? "رله " + (index + 1) : "رجیستر " + (index + 1);
                    if (coil) {
                        coilNames[index] = value;
                        prefs.edit().putString("coil_name_" + index, value).apply();
                    } else {
                        regNames[index] = value;
                        prefs.edit().putString("reg_name_" + index, value).apply();
                    }
                    render();
                }).show();
    }

    private void editRegisterValue(int index) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(regKnown[index] ? String.valueOf(regValue[index]) : "0");
        input.setTextColor(TEXT);
        input.setPadding(dp(12), dp(6), dp(12), dp(6));
        input.setBackground(round(CARD_DARK, 10, BORDER));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(4), dp(20), dp(4));
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        new AlertDialog.Builder(this)
                .setTitle("ثبت مقدار برای " + regNames[index])
                .setMessage("مقدار عدد صحیح بین 0 و 65535")
                .setView(wrap)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ارسال SET", (dialog, which) -> {
                    String raw = input.getText().toString().trim();
                    int value;
                    try { value = Integer.parseInt(raw); } catch (NumberFormatException ex) { value = -1; }
                    if (value < 0 || value > 65535) {
                        toast("مقدار رجیستر باید بین 0 و 65535 باشد.");
                        return;
                    }
                    sendCommand("SET " + (index + 1) + " " + value, "REGWRITE", index, false);
                }).show();
    }

    private void sendCommand(String command, String expected, int index, boolean refreshSequence) {
        if (waiting) return;
        if (!hasSmsPermissions()) {
            ensureSmsPermissions();
            stateMessage = "ابتدا اجازهٔ ارسال و دریافت پیامک را فعال کنید.";
            render();
            return;
        }
        String dest = normalizePhone(deviceNumber());
        if (dest.length() < 10) {
            stateMessage = "شمارهٔ سیم‌کارت داخل کنترلر وارد نشده است.";
            toast("ابتدا شمارهٔ سیم‌کارت کنترلر را وارد و ذخیره کنید.");
            render();
            return;
        }
        waiting = true;
        expectedType = expected;
        expectedIndex = index;
        pendingCommand = command;
        activeToken = nextToken();
        final long token = activeToken;
        stateMessage = "فرمان «" + command + "» ارسال شد؛ منتظر پیام برگشتی هستیم.";
        armTimeout(token);
        render();
        transmitSms(dest, command, token);
    }

    private void transmitSms(String dest, String command, long token) {
        try {
            SmsManager manager = SmsManager.getDefault();
            Intent sent = new Intent(this, SmsSentReceiver.class);
            sent.setAction(SmsSentReceiver.ACTION_SENT);
            sent.putExtra("token", token);
            int req = (int) (token & 0x7fffffff);
            PendingIntent sentPI = PendingIntent.getBroadcast(this, req, sent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.sendTextMessage(dest, null, command, sentPI, null);
        } catch (Exception ex) {
            finishTransaction("ارسال پیامک شروع نشد: " + safeMessage(ex));
        }
    }

    private void armTimeout(long token) {
        handler.postDelayed(() -> {
            if (!waiting || activeToken != token) return;
            String oldType = expectedType;
            int oldIndex = expectedIndex;
            waiting = false;
            expectedType = "";
            expectedIndex = -1;
            pendingCommand = "";
            if ("RELAY".equals(oldType) && oldIndex >= 0 && oldIndex < NUM_COILS) coilState[oldIndex] = -1;
            if ("STATUS".equals(oldType)) java.util.Arrays.fill(coilState, -1);
            if ("VARS".equals(oldType)) for (int i = 0; i < NUM_REGS; i++) regKnown[i] = false;
            stateMessage = "تا ۹۰ ثانیه پاسخی نرسید. برای جلوگیری از اجرای تکراری، فرمان خودکار دوباره ارسال نشد؛ وضعیت را رفرش کنید.";
            render();
        }, RESPONSE_TIMEOUT_MS);
    }

    private void queueVarsAfterStatus() {
        // Keep the UI locked across both phases of a refresh transaction.
        expectedType = "VARS";
        expectedIndex = -1;
        pendingCommand = "VARS";
        activeToken = nextToken();
        long token = activeToken;
        stateMessage = "وضعیت رله‌ها دریافت شد؛ در حال درخواست مقدار رجیسترها...";
        armTimeout(token);
        render();
        handler.postDelayed(() -> {
            if (waiting && activeToken == token) transmitSms(normalizePhone(deviceNumber()), "VARS", token);
        }, 650L);
    }

    private void finishTransaction(String message) {
        waiting = false;
        expectedType = "";
        expectedIndex = -1;
        pendingCommand = "";
        activeToken = nextToken(); // invalidates old timeout and send callbacks
        stateMessage = message;
        render();
    }

    private long nextToken() {
        tokenCounter = Math.max(System.currentTimeMillis(), tokenCounter + 1L);
        return tokenCounter;
    }

    private void processStoredSmsIfNew() {
        long id = smsStore.getLong("last_id", 0L);
        if (id <= prefs.getLong("last_processed_sms_id", 0L)) return;
        handleIncomingOnce(smsStore.getString("last_sender", ""), smsStore.getString("last_body", ""), id);
    }

    private void handleIncomingOnce(String sender, String body, long id) {
        if (id <= 0L || id <= prefs.getLong("last_processed_sms_id", 0L)) return;
        prefs.edit().putLong("last_processed_sms_id", id).apply();
        if (sender == null || body == null) return;
        String configured = normalizePhone(deviceNumber());
        String incoming = normalizePhone(sender);
        if (configured.isEmpty() || !configured.equals(incoming)) {
            // Incoming messages from other senders are intentionally not shown as controller feedback.
            return;
        }

        String cleanBody = body.trim();
        lastReply = cleanBody;
        lastReplyAt = System.currentTimeMillis();
        prefs.edit().putString("last_reply", lastReply).putLong("last_reply_at", lastReplyAt).apply();

        int coilUpdates = parseCoilStates(cleanBody);
        int regUpdates = parseRegisterValues(cleanBody);
        boolean powerRestored = cleanBody.toLowerCase(Locale.ROOT).contains("power restored");
        if (powerRestored) {
            stateMessage = "پیام راه‌اندازی کنترلر دریافت شد.";
            render();
            return;
        }

        if (waiting) {
            if ("STATUS".equals(expectedType)) {
                if (coilUpdates > 0 && (cleanBody.toLowerCase(Locale.ROOT).contains("coils:") ||
                        Pattern.compile("(?i)\\b\\d+\\s*:\\s*(ON|OFF|PULSE)").matcher(cleanBody).find())) {
                    queueVarsAfterStatus();
                    return;
                } else if (isControllerError(cleanBody)) {
                    finishTransaction("کنترلر پاسخ خطا داد؛ متن پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("VARS".equals(expectedType)) {
                if (regUpdates > 0) {
                    finishTransaction("رجیسترها از پیامک به‌روزرسانی شدند.");
                    return;
                } else if (isControllerError(cleanBody)) {
                    finishTransaction("کنترلر پاسخ خطا داد؛ متن پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("RELAY".equals(expectedType)) {
                if (containsSingleCoilResponse(cleanBody, expectedIndex + 1) || isControllerError(cleanBody)) {
                    finishTransaction(containsSingleCoilResponse(cleanBody, expectedIndex + 1)
                            ? "فیدبک رله دریافت شد؛ وضعیت به‌روزرسانی شد." : "کنترلر فرمان رله را نپذیرفت؛ پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("REGWRITE".equals(expectedType)) {
                if (containsSingleRegResponse(cleanBody, expectedIndex + 1) || isControllerError(cleanBody)) {
                    finishTransaction(containsSingleRegResponse(cleanBody, expectedIndex + 1)
                            ? "فیدبک نوشتن رجیستر دریافت شد." : "کنترلر مقدار رجیستر را نپذیرفت؛ پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("PULSE_CMD".equals(expectedType)) {
                Pattern pulseReply = Pattern.compile("(?i)\\bPulse\\s+on\\s+coil\\s+" + (expectedIndex + 1) + "\\b");
                if (pulseReply.matcher(cleanBody).find()) {
                    if (expectedIndex >= 0 && expectedIndex < NUM_COILS) coilState[expectedIndex] = 2;
                    finishTransaction("فرمان پالس تأیید شد.");
                    return;
                } else if (isControllerError(cleanBody)) {
                    finishTransaction("کنترلر فرمان پالس را نپذیرفت؛ پاسخ را بررسی کنید.");
                    return;
                }
            } else if ("ADMIN".equals(expectedType)) {
                if (cleanBody.toLowerCase(Locale.ROOT).contains("enter number with *")) {
                    adminNeedsNumber = true;
                    adminFollowupCommand = pendingCommand;
                    prefs.edit().putBoolean("admin_needs_number", true)
                            .putString("admin_followup_command", adminFollowupCommand).apply();
                    finishTransaction("کنترلر شمارهٔ مقصد را خواسته است؛ آن را در همین تب وارد کنید.");
                    currentTab = 2;
                    render();
                    return;
                }
                finishTransaction("پاسخ فرمان مدیریتی دریافت شد.");
                return;
            } else if ("ADMIN_NUM".equals(expectedType)) {
                adminNeedsNumber = false;
                adminFollowupCommand = "";
                prefs.edit().putBoolean("admin_needs_number", false).putString("admin_followup_command", "").apply();
                finishTransaction("پاسخ تکمیل فرمان مدیریتی دریافت شد.");
                return;
            }
        }

        if (coilUpdates > 0 || regUpdates > 0) stateMessage = "فیدبک پیامکی دریافت شد؛ مقادیر متناظر به‌روزرسانی شدند.";
        else stateMessage = "پیام از سیم‌کارت کنترلر دریافت شد.";
        render();
    }

    private int parseCoilStates(String body) {
        int updates = 0;
        Pattern statusList = Pattern.compile("(?i)\\b(\\d{1,2})\\s*:\\s*(ON|OFF|PULSE)\\b");
        Matcher listMatcher = statusList.matcher(body);
        while (listMatcher.find()) {
            int number = parseInt(listMatcher.group(1), -1);
            int state = coilStateFrom(listMatcher.group(2));
            if (number >= 1 && number <= NUM_COILS && state >= 0) {
                coilState[number - 1] = state;
                updates++;
            }
        }
        Pattern single = Pattern.compile("(?i)\\bCoil\\s+(\\d{1,2})\\s*=\\s*(ON|OFF|PULSE)\\b");
        Matcher singleMatcher = single.matcher(body);
        while (singleMatcher.find()) {
            int number = parseInt(singleMatcher.group(1), -1);
            int state = coilStateFrom(singleMatcher.group(2));
            if (number >= 1 && number <= NUM_COILS && state >= 0) {
                coilState[number - 1] = state;
                updates++;
            }
        }
        return updates;
    }

    private int parseRegisterValues(String body) {
        int updates = 0;
        Pattern list = Pattern.compile("(?i)\\bR\\s*(\\d{1,2})\\s*:\\s*(-?\\d+)");
        Matcher m = list.matcher(body);
        while (m.find()) {
            int number = parseInt(m.group(1), -1);
            int value = parseInt(m.group(2), -1);
            if (number >= 1 && number <= NUM_REGS && value >= 0 && value <= 65535) {
                regValue[number - 1] = value;
                regKnown[number - 1] = true;
                updates++;
            }
        }
        Pattern single = Pattern.compile("(?i)\\bReg\\s*(\\d{1,2})\\s*=\\s*(-?\\d+)");
        Matcher sm = single.matcher(body);
        while (sm.find()) {
            int number = parseInt(sm.group(1), -1);
            int value = parseInt(sm.group(2), -1);
            if (number >= 1 && number <= NUM_REGS && value >= 0 && value <= 65535) {
                regValue[number - 1] = value;
                regKnown[number - 1] = true;
                updates++;
            }
        }
        return updates;
    }

    private boolean containsSingleCoilResponse(String body, int number) {
        Pattern p = Pattern.compile("(?i)\\bCoil\\s+" + number + "\\s*=\\s*(ON|OFF|PULSE)\\b");
        return p.matcher(body).find();
    }

    private boolean containsSingleRegResponse(String body, int number) {
        Pattern p = Pattern.compile("(?i)\\bReg\\s*" + number + "\\s*=\\s*-?\\d+");
        return p.matcher(body).find();
    }

    private boolean isControllerError(String body) {
        String low = body.toLowerCase(Locale.ROOT);
        return low.contains("access denied") || low.contains("admin only") || low.contains("invalid") ||
                low.contains("usage:") || low.contains("user not found") || low.contains("not registered") ||
                low.contains("failed") || low.contains("all invite slots full") || low.contains("user exists or full");
    }

    private int coilStateFrom(String state) {
        if (state == null) return -1;
        if (state.equalsIgnoreCase("ON")) return 1;
        if (state.equalsIgnoreCase("OFF")) return 0;
        if (state.equalsIgnoreCase("PULSE")) return 2;
        return -1;
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ex) { return fallback; }
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int d = Character.digit(c, 10);
            if (d >= 0) digits.append((char) ('0' + d));
        }
        String value = digits.toString();
        if (value.startsWith("0098")) value = value.substring(2);
        else if (value.startsWith("0")) value = "98" + value.substring(1);
        else if (value.startsWith("9") && value.length() == 10) value = "98" + value;
        return value;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) return ex.getClass().getSimpleName();
        return message;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.setBackground(round(CARD, 15, BORDER));
        return c;
    }

    private LinearLayout sectionTitle(String title, String detail) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 17, TEXT, true);
        wrap.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView d = text(detail, 11, MUTED, true);
        wrap.addView(d);
        wrap.setPadding(dp(2), dp(4), dp(2), dp(12));
        return wrap;
    }

    private void addSectionTitle(String title, String detail) {
        contentHost.addView(sectionTitle(title, detail));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setLineSpacing(dp(2), 1.0f);
        return v;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(foreground);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(5), dp(10), dp(5));
        b.setBackground(round(background, 11, background == CARD_DARK ? BORDER : background));
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), strokeColor);
        return gd;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return lp;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
