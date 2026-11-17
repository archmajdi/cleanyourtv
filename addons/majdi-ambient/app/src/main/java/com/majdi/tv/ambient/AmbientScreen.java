package com.majdi.tv.ambient;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dashboard-style screensaver, laid out like a wall clock:
 *   top right   big clock, date line, Ramadan countdown and Al-Ahli, gold and the dollar
 *   top left    the next prayer: time, time left, time since the last one
 *   middle      the five prayers in one row, the next one framed
 *   bottom      Riyadh's weather now and the next seven days
 *   last line   one quiet dhikr
 * Everything drifts a few pixels each minute so nothing burns into the panel.
 */
final class AmbientScreen {
    private static final String TAG = "MajdiAmbient";
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long RETRY = 5 * MIN;

    private static final String[] KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] NAMES = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final int[] PRAYERS = {0, 2, 3, 4, 5};
    private static final Locale AR = Locale.forLanguageTag("ar-u-nu-latn");
    private static final TimeZone RIYADH = TimeZone.getTimeZone("Asia/Riyadh");

    private static final int ACCENT = 0xFFA9CCFF;
    private static final int CARD_FILL = 0x7012204A;
    private static final int CARD_LINE = 0x665F86E8;

    private final Context ctx;
    private final Resources res;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();
    private final float density;
    private final float level;
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final FrameLayout root;
    private final LinearLayout content;
    private final GlowView glow;

    private TextView clock, ampm, dateLine, ramadanLine, marketLine, dhikr;
    private TextView nextName, nextTime, nextLeft, sinceLast;
    private LinearLayout nextCard, prayerCard, weatherCard, weatherDays;
    private final LinearLayout[] prayerCell = new LinearLayout[5];
    private final TextView[] prayerName = new TextView[5];
    private final TextView[] prayerTime = new TextView[5];
    private TextView cityName, cityNow;

    private volatile boolean running;
    private boolean destroyed;

    private String[] times;
    private String timesDay;
    private String hijriText = "";
    private int hijriDay, hijriMonth, hijriYear;
    private String ramadanText = "", matchText = "", goldText = "", usdText = "";
    private long weatherAt, goldAt, usdAt, matchAt, prayersAt, ramadanAt, btcAt;
    private int dhikrIndex;

    AmbientScreen(Context context) {
        ctx = context;
        res = context.getResources();
        prefs = context.getSharedPreferences("ambient", Context.MODE_PRIVATE);
        density = res.getDisplayMetrics().density;
        level = Math.max(20, Math.min(100, res.getInteger(R.integer.text_alpha) + 15)) / 100f;
        dhikrIndex = rnd.nextInt(100);

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0xFF060A18);
        glow = new GlowView(ctx);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setPadding(dp(48), dp(30), dp(48), dp(24));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        build();
        loadCache();
        render();
    }

    View view() {
        return root;
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        // Top: clock column on the right, next-prayer card on the left (RTL row).
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout clockCol = new LinearLayout(ctx);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout clockRow = new LinearLayout(ctx);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(66, 100, light, ACCENT);
        ampm = text(20, 85, regular, ACCENT);
        clockRow.addView(clock);
        LinearLayout.LayoutParams al = wrap();
        al.setMarginStart(dp(10));
        al.bottomMargin = dp(10);
        clockRow.addView(ampm, al);
        clockCol.addView(clockRow);
        dateLine = text(19, 90, regular, Color.WHITE);
        ramadanLine = text(17, 80, regular, Color.WHITE);
        marketLine = text(16, 70, regular, Color.WHITE);
        clockCol.addView(dateLine, gap(10));
        clockCol.addView(ramadanLine, gap(10));
        clockCol.addView(marketLine, gap(8));
        top.addView(clockCol, new LinearLayout.LayoutParams(0, -2, 1f));

        nextCard = card();
        nextCard.setMinimumWidth(dp(330));
        TextView head = text(15, 70, regular, Color.WHITE);
        head.setText("الصلاة القادمة");
        LinearLayout nameRow = new LinearLayout(ctx);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.BOTTOM);
        nextName = text(32, 100, medium, Color.WHITE);
        nextTime = text(20, 85, regular, Color.WHITE);
        nameRow.addView(nextName);
        LinearLayout.LayoutParams tl = wrap();
        tl.setMarginStart(dp(16));
        tl.bottomMargin = dp(4);
        nameRow.addView(nextTime, tl);
        nextLeft = text(20, 100, regular, ACCENT);
        sinceLast = text(14, 65, regular, Color.WHITE);
        nextCard.addView(head);
        nextCard.addView(nameRow, gap(10));
        nextCard.addView(nextLeft, gap(10));
        nextCard.addView(sinceLast, gap(8));
        top.addView(nextCard, wrap());
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // Middle: the five prayers.
        prayerCard = card();
        prayerCard.setOrientation(LinearLayout.HORIZONTAL);
        prayerCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        for (int c = 0; c < 5; c++) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(10), 0, dp(10));
            prayerName[c] = text(17, 85, regular, Color.WHITE);
            prayerTime[c] = text(24, 100, regular, Color.WHITE);
            prayerName[c].setGravity(Gravity.CENTER);
            prayerTime[c].setGravity(Gravity.CENTER);
            cell.addView(prayerName[c]);
            cell.addView(prayerTime[c], gap(8));
            prayerCell[c] = cell;
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            cl.setMarginStart(c == 0 ? 0 : dp(8));
            prayerCard.addView(cell, cl);
        }
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = dp(22);
        content.addView(prayerCard, pl);

        // Bottom: weather for the city, now and the next days.
        weatherCard = card();
        weatherCard.setOrientation(LinearLayout.HORIZONTAL);
        weatherCard.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout city = new LinearLayout(ctx);
        city.setOrientation(LinearLayout.VERTICAL);
        cityName = text(22, 100, medium, Color.WHITE);
        cityName.setText(res.getString(R.string.place));
        cityNow = text(24, 100, regular, ACCENT);
        city.addView(cityName);
        city.addView(cityNow, gap(8));
        weatherCard.addView(city, new LinearLayout.LayoutParams(dp(150), -2));
        weatherDays = new LinearLayout(ctx);
        weatherDays.setOrientation(LinearLayout.HORIZONTAL);
        weatherCard.addView(weatherDays, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(18);
        content.addView(weatherCard, wl);

        // Last line: the dhikr.
        dhikr = text(18, 70, light, Color.WHITE);
        dhikr.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = dp(18);
        content.addView(dhikr, dl);

        nextCard.setVisibility(View.INVISIBLE);
        prayerCard.setVisibility(View.GONE);
        weatherCard.setVisibility(View.GONE);
        ramadanLine.setVisibility(View.GONE);
        marketLine.setVisibility(View.GONE);
        if (!res.getBoolean(R.bool.show_adhkar)) dhikr.setVisibility(View.GONE);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(frame(CARD_FILL, CARD_LINE, 22));
        l.setPadding(dp(26), dp(18), dp(26), dp(18));
        return l;
    }

    private GradientDrawable frame(int fill, int line, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radius));
        d.setColor(fill);
        d.setStroke(Math.max(1, dp(1)), line);
        return d;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private LinearLayout.LayoutParams gap(int topDp) {
        LinearLayout.LayoutParams p = wrap();
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView text(float sp, int strength, Typeface face, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(tint(color, strength));
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setShadowLayer(dp(6), 0, 0, 0x99000000);
        return t;
    }

    private int tint(int color, int strength) {
        int a = Math.round(255f * level * strength / 100f);
        return (color & 0x00FFFFFF) | (Math.min(255, a) << 24);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- lifecycle

    void start() {
        if (destroyed || running) return;
        running = true;
        main.removeCallbacksAndMessages(null);
        glow.setRunning(true);
        tick.run();
        nextDhikr.run();
    }

    void stop() {
        running = false;
        glow.setRunning(false);
        main.removeCallbacksAndMessages(null);
    }

    void destroy() {
        stop();
        destroyed = true;
        io.shutdownNow();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            render();
            drift();
            refreshIfDue();
            long now = System.currentTimeMillis();
            main.postDelayed(this, MIN - (now % MIN) + 200);
        }
    };

    private final Runnable nextDhikr = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            showDhikr();
            main.postDelayed(this, Math.max(10, res.getInteger(R.integer.dhikr_seconds)) * 1000L);
        }
    };

    private void drift() {
        float r = dp(8);
        content.animate()
                .translationX((rnd.nextFloat() * 2 - 1) * r)
                .translationY((rnd.nextFloat() * 2 - 1) * r)
                .setDuration(3000)
                .start();
    }

    private void showDhikr() {
        if (dhikr.getVisibility() == View.GONE) return;
        final String next = pickDhikr();
        dhikr.animate().alpha(0f).setDuration(1500).withEndAction(() -> {
            dhikr.setText(next);
            dhikr.animate().alpha(1f).setDuration(1500).start();
        }).start();
    }

    private String pickDhikr() {
        String[] timed = isMorning() ? Adhkar.MORNING : Adhkar.EVENING;
        List<String> all = new ArrayList<>();
        for (String s : timed) all.add(s);
        for (String s : Adhkar.GENERAL) all.add(s);
        dhikrIndex = (dhikrIndex + 1) % all.size();
        return all.get(dhikrIndex);
    }

    private boolean isMorning() {
        int now = minutesNow();
        int fajr = 4 * 60, asr = 15 * 60;
        if (times != null) {
            fajr = toMinutes(times[0], fajr);
            asr = toMinutes(times[3], asr);
        }
        return now >= fajr && now < asr;
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        Date now = new Date();
        Calendar c = Calendar.getInstance(RIYADH);
        int h = c.get(Calendar.HOUR_OF_DAY) % 12;
        clock.setText((h == 0 ? 12 : h) + ":" + String.format(Locale.US, "%02d", c.get(Calendar.MINUTE)));
        ampm.setText(c.get(Calendar.HOUR_OF_DAY) < 12 ? "ص" : "م");
        String d = fmt("EEEE، d MMMM", AR).format(now);
        dateLine.setText(TextUtils.isEmpty(hijriText) ? d : d + "   ·   " + hijriText);

        String rm = ramadanText;
        if (!TextUtils.isEmpty(matchText)) rm = TextUtils.isEmpty(rm) ? matchText : rm + "      " + matchText;
        ramadanLine.setText(rm);
        ramadanLine.setVisibility(TextUtils.isEmpty(rm) ? View.GONE : View.VISIBLE);

        String mk = goldText;
        if (!TextUtils.isEmpty(usdText)) mk = TextUtils.isEmpty(mk) ? usdText : mk + "      " + usdText;
        marketLine.setText(mk);
        marketLine.setVisibility(TextUtils.isEmpty(mk) ? View.GONE : View.VISIBLE);

        renderPrayers();
        renderSky();
    }

    private void renderPrayers() {
        if (!res.getBoolean(R.bool.show_prayers) || times == null || !today().equals(timesDay)) {
            nextCard.setVisibility(View.INVISIBLE);
            prayerCard.setVisibility(View.GONE);
            return;
        }
        int now = minutesNow();
        int next = -1, prev = -1;
        for (int i : PRAYERS) {
            int t = toMinutes(times[i], -1);
            if (t > now && next < 0) next = i;
            if (t <= now) prev = i;
        }
        int left, since;
        if (next < 0) { // after Isha: Fajr tomorrow
            next = 0;
            left = toMinutes(times[0], 0) + 1440 - now;
        } else {
            left = toMinutes(times[next], now) - now;
        }
        if (prev < 0) { // before Fajr: since last night's Isha
            prev = 5;
            since = now + 1440 - toMinutes(times[5], 0);
        } else {
            since = now - toMinutes(times[prev], now);
        }
        nextName.setText(NAMES[next]);
        nextTime.setText(ampmText(times[next]));
        nextLeft.setText("باقي " + duration(left));
        sinceLast.setText("مضى على " + NAMES[prev] + " " + duration(since));
        nextCard.setVisibility(View.VISIBLE);

        for (int c = 0; c < PRAYERS.length; c++) {
            int i = PRAYERS[c];
            boolean on = i == next;
            prayerName[c].setText(NAMES[i]);
            prayerTime[c].setText(ampmText(times[i]));
            prayerCell[c].setBackground(on ? frame(0x40FFFFFF, 0x99FFFFFF, 16) : null);
            prayerName[c].setTextColor(tint(Color.WHITE, on ? 100 : 75));
            prayerTime[c].setTextColor(tint(on ? Color.WHITE : ACCENT, on ? 100 : 90));
        }
        prayerCard.setVisibility(View.VISIBLE);
    }

    /** "8 ساعات و44 دقيقة" */
    private static String duration(int minutes) {
        int h = Math.max(0, minutes) / 60, m = Math.max(0, minutes) % 60;
        String hs = h == 0 ? "" : h == 1 ? "ساعة" : h == 2 ? "ساعتان" : h <= 10 ? h + " ساعات" : h + " ساعة";
        String ms = m == 0 ? "" : m == 1 ? "دقيقة" : m == 2 ? "دقيقتان" : m <= 10 ? m + " دقائق" : m + " دقيقة";
        if (hs.isEmpty() && ms.isEmpty()) return "أقل من دقيقة";
        if (hs.isEmpty()) return ms;
        if (ms.isEmpty()) return hs;
        return hs + " و" + ms;
    }

    private static String days(int n) {
        if (n == 1) return "يوم واحد";
        if (n == 2) return "يومان";
        if (n >= 3 && n <= 10) return n + " أيام";
        return n + " يومًا";
    }

    /** "4:48 ص" */
    private static String ampmText(String hhmm) {
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            String m = hhmm.substring(3, 5);
            String s = h < 12 ? "ص" : "م";
            h = h % 12;
            if (h == 0) h = 12;
            return h + ":" + m + " " + s;
        } catch (Exception e) {
            return hhmm;
        }
    }

    // Dawn, day, golden afternoon, sunset, night: the glow follows the prayer times.
    private static final int[] DAWN = {0xFF2A3A80, 0xFF6A3A62, 0xFF7A5038};
    private static final int[] DAY = {0xFF0E4E6A, 0xFF183C80, 0xFF175A50};
    private static final int[] GOLDEN = {0xFF7A4A22, 0xFF62283A, 0xFF382A66};
    private static final int[] DUSK = {0xFF2A1F5A, 0xFF5A264E, 0xFF16365A};
    private static final int[] NIGHT = {0xFF16205A, 0xFF34205C, 0xFF0B3846};

    private void renderSky() {
        int now = minutesNow();
        int fajr = 290, sunrise = 370, asr = 900, maghrib = 1030, isha = 1120;
        if (times != null) {
            fajr = toMinutes(times[0], fajr);
            sunrise = toMinutes(times[1], sunrise);
            asr = toMinutes(times[3], asr);
            maghrib = toMinutes(times[4], maghrib);
            isha = toMinutes(times[5], isha);
        }
        if (now >= fajr && now < sunrise + 30) glow.setPalette(DAWN, false);
        else if (now >= sunrise + 30 && now < asr) glow.setPalette(DAY, false);
        else if (now >= asr && now < maghrib) glow.setPalette(GOLDEN, false);
        else if (now >= maghrib && now < isha) glow.setPalette(DUSK, true);
        else glow.setPalette(NIGHT, true);
    }

    // ---------------------------------------------------------------- refresh

    private void refreshIfDue() {
        long now = System.currentTimeMillis();
        if (res.getBoolean(R.bool.show_prayers) && (!today().equals(timesDay) || now - prayersAt > 6 * HOUR)) {
            prayersAt = now - 6 * HOUR + RETRY;
            io.execute(this::fetchPrayers);
        }
        if (hijriYear > 0 && now - ramadanAt > 6 * HOUR) {
            ramadanAt = now - 6 * HOUR + RETRY;
            io.execute(this::fetchRamadan);
        }
        if (res.getBoolean(R.bool.show_weather) && now - weatherAt > 30 * MIN) {
            weatherAt = now - 30 * MIN + RETRY;
            io.execute(this::fetchWeather);
        }
        if (res.getBoolean(R.bool.show_gold) && now - goldAt > HOUR) {
            goldAt = now - HOUR + RETRY;
            io.execute(this::fetchGold);
        }
        if (res.getBoolean(R.bool.show_usd) && now - btcAt > 30 * MIN) {
            btcAt = now - 30 * MIN + RETRY;
            io.execute(this::fetchBtc);
        }
        if (now - usdAt > 6 * HOUR) {
            usdAt = now - 6 * HOUR + RETRY;
            io.execute(this::fetchUsd);
        }
        if (res.getBoolean(R.bool.show_match) && now - matchAt > 6 * HOUR) {
            matchAt = now - 6 * HOUR + RETRY;
            io.execute(this::fetchMatch);
        }
    }

    private String lat() {
        return res.getString(R.string.latitude);
    }

    private String lon() {
        return res.getString(R.string.longitude);
    }

    private void fetchPrayers() {
        try {
            String day = today();
            String body = Net.get("https://api.aladhan.com/v1/timings/" + day + "?latitude=" + lat()
                    + "&longitude=" + lon() + "&method=" + res.getInteger(R.integer.method) + "&calendarMethod=UAQ");
            applyPrayers(body, day);
            prefs.edit().putString("prayers", body).putString("prayersDay", day).apply();
            prayersAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "prayers", e);
        }
    }

    private void applyPrayers(String body, String day) throws Exception {
        JSONObject data = new JSONObject(body).getJSONObject("data");
        JSONObject t = data.getJSONObject("timings");
        final String[] out = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) out[i] = t.getString(KEYS[i]).trim().substring(0, 5);
        JSONObject date = data.optJSONObject("date");
        JSONObject hj = date == null ? null : date.optJSONObject("hijri");
        String text = "";
        int hd = 0, hm = 0, hy = 0;
        if (hj != null) {
            JSONObject month = hj.optJSONObject("month");
            hd = parseInt(hj.optString("day"));
            hy = parseInt(hj.optString("year"));
            hm = month == null ? 0 : month.optInt("number", 0);
            text = (hd + " " + (month == null ? "" : month.optString("ar", "")) + " " + hy + " هـ").trim();
        }
        final String ht = text;
        final int fd = hd, fm = hm, fy = hy;
        post(() -> {
            times = out;
            timesDay = day;
            hijriText = ht;
            boolean newYear = fy != hijriYear || fm != hijriMonth;
            hijriDay = fd;
            hijriMonth = fm;
            hijriYear = fy;
            if (newYear) ramadanAt = 0;
            updateRamadanText();
            render();
        });
    }

    /** First of Ramadan in the Gregorian calendar, from Aladhan's Umm al-Qura conversion. */
    private void fetchRamadan() {
        try {
            int y = hijriMonth > 9 ? hijriYear + 1 : hijriYear;
            String key = "ramadan" + y;
            if (!prefs.contains(key)) {
                String body = Net.get("https://api.aladhan.com/v1/hToG/01-09-" + y + "?calendarMethod=UAQ");
                String g = new JSONObject(body).getJSONObject("data").getJSONObject("gregorian").getString("date");
                prefs.edit().putString(key, g).apply();
            }
            ramadanAt = System.currentTimeMillis();
            post(() -> {
                updateRamadanText();
                render();
            });
        } catch (Exception e) {
            Log.w(TAG, "ramadan", e);
        }
    }

    private void updateRamadanText() {
        if (hijriMonth == 9) {
            ramadanText = "🌙  رمضان مبارك · اليوم " + hijriDay;
            return;
        }
        int y = hijriMonth > 9 ? hijriYear + 1 : hijriYear;
        String g = prefs.getString("ramadan" + y, null);
        if (g == null) {
            ramadanText = "";
            return;
        }
        try {
            Date start = fmt("dd-MM-yyyy", Locale.US).parse(g);
            Date today = fmt("dd-MM-yyyy", Locale.US).parse(today());
            int n = (int) Math.round((start.getTime() - today.getTime()) / 86_400_000.0);
            ramadanText = n > 0 ? "🌙  باقي على رمضان " + days(n) : "";
        } catch (Exception e) {
            ramadanText = "";
        }
    }

    private void fetchWeather() {
        try {
            String body = Net.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat() + "&longitude=" + lon()
                    + "&current=temperature_2m,weather_code"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=7&timezone=Asia%2FRiyadh");
            showWeather(body);
            prefs.edit().putString("weather", body).apply();
            weatherAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "weather", e);
        }
    }

    private void showWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        JSONObject cur = o.getJSONObject("current");
        final String now = icon(cur.optInt("weather_code", -1)) + "  " + Math.round(cur.getDouble("temperature_2m")) + "°";
        JSONObject daily = o.optJSONObject("daily");
        final List<String[]> list = new ArrayList<>();
        if (daily != null) {
            JSONArray d = daily.getJSONArray("time");
            JSONArray code = daily.getJSONArray("weather_code");
            JSONArray hi = daily.getJSONArray("temperature_2m_max");
            JSONArray lo = daily.getJSONArray("temperature_2m_min");
            SimpleDateFormat in = fmt("yyyy-MM-dd", Locale.US);
            SimpleDateFormat dayName = fmt("EEEE", AR);
            String todayIso = fmt("yyyy-MM-dd", Locale.US).format(new Date());
            for (int i = 0; i < d.length() && list.size() < 7; i++) {
                String iso = d.getString(i);
                if (iso.compareTo(todayIso) < 0) continue;
                String name = iso.equals(todayIso) ? "اليوم" : dayName.format(in.parse(iso));
                list.add(new String[]{name, icon(code.optInt(i, -1)),
                        Math.round(hi.getDouble(i)) + "°", Math.round(lo.getDouble(i)) + "°"});
            }
        }
        post(() -> {
            cityNow.setText(now);
            weatherDays.removeAllViews();
            for (String[] day : list) {
                LinearLayout cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                TextView n = text(16, 80, regular, Color.WHITE);
                TextView ic = text(22, 100, regular, Color.WHITE);
                TextView t = text(18, 100, regular, Color.WHITE);
                n.setText(day[0]);
                ic.setText(day[1]);
                t.setText(day[2] + " / " + day[3]);
                n.setGravity(Gravity.CENTER);
                ic.setGravity(Gravity.CENTER);
                t.setGravity(Gravity.CENTER);
                t.setTextDirection(View.TEXT_DIRECTION_LTR);
                cell.addView(n);
                cell.addView(ic, gap(8));
                cell.addView(t, gap(8));
                weatherDays.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            weatherCard.setVisibility(View.VISIBLE);
        });
    }

    private static String icon(int code) {
        if (code == 0 || code == 1) return "☀︎";
        if (code == 2) return "⛅";
        if (code == 3 || code == 45 || code == 48) return "☁︎";
        if (code >= 51 && code <= 67) return "🌧";
        if (code >= 71 && code <= 77) return "❄︎";
        if (code >= 80 && code <= 82) return "🌦";
        if (code >= 95) return "⛈";
        return "";
    }

    private void fetchGold() {
        try {
            String body = Net.get("https://api.gold-api.com/price/XAU");
            showGold(body);
            prefs.edit().putString("gold", body).apply();
            goldAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "gold", e);
        }
    }

    private void showGold(String body) throws Exception {
        double usdOunce = new JSONObject(body).getDouble("price");
        double rate = prefs.getFloat("sar", 3.75f);
        long g24 = Math.round(usdOunce * rate / 31.1034768);
        long g21 = Math.round(usdOunce * rate / 31.1034768 * 21.0 / 24.0);
        final String line = "ذهب 24: " + g24 + " ر.س   ·   ذهب 21: " + g21 + " ر.س";
        post(() -> {
            goldText = line;
            render();
        });
    }

    private void fetchUsd() {
        try {
            String body = Net.get("https://open.er-api.com/v6/latest/USD");
            double sar = new JSONObject(body).getJSONObject("rates").getDouble("SAR");
            prefs.edit().putFloat("sar", (float) sar).apply();
            showUsd(sar);
            usdAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "usd", e);
        }
    }

    private void showUsd(double sar) {
        // The rate is kept only to convert gold to riyals; Bitcoin takes the dollar's place on screen.
    }

    private void fetchBtc() {
        try {
            String body = Net.get("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd&include_24hr_change=true");
            showBtc(body);
            prefs.edit().putString("btc", body).apply();
            btcAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "btc", e);
        }
    }

    private void showBtc(String body) throws Exception {
        JSONObject b = new JSONObject(body).getJSONObject("bitcoin");
        long price = Math.round(b.getDouble("usd"));
        double ch = b.optDouble("usd_24h_change", 0);
        String arrow = ch >= 0 ? "▲" : "▼";
        final String line = "₿ بيتكوين: " + String.format(Locale.US, "%,d", price) + " $  "
                + arrow + " " + String.format(Locale.US, "%.1f", Math.abs(ch)) + "%";
        post(() -> {
            usdText = line;
            render();
        });
    }

    private void fetchMatch() {
        try {
            String id = prefs.getString("teamId", res.getString(R.string.team_id));
            if (TextUtils.isEmpty(id)) return;
            String body = Net.get("https://www.thesportsdb.com/api/v1/json/123/eventsnext.php?id=" + id);
            showMatch(body, id);
            prefs.edit().putString("match", body).apply();
            matchAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "match", e);
        }
    }

    private void showMatch(String body, String teamId) throws Exception {
        JSONArray events = new JSONObject(body).optJSONArray("events");
        String line = "";
        long now = System.currentTimeMillis();
        if (events != null) {
            for (int i = 0; i < events.length() && line.isEmpty(); i++) {
                JSONObject e = events.getJSONObject(i);
                String stamp = e.optString("strTimestamp", "");
                Date when;
                if (stamp.length() >= 19) {
                    when = utc("yyyy-MM-dd'T'HH:mm:ss").parse(stamp.substring(0, 19));
                } else {
                    String time = e.optString("strTime", "00:00:00");
                    if (time.length() < 8) time = "00:00:00";
                    when = utc("yyyy-MM-dd HH:mm:ss").parse(e.optString("dateEvent") + " " + time.substring(0, 8));
                }
                if (when == null || when.getTime() < now - 2 * HOUR || when.getTime() > now + 14 * 24 * HOUR) continue;
                boolean home = teamId.equals(e.optString("idHomeTeam"));
                String other = home ? e.optString("strAwayTeam") : e.optString("strHomeTeam");
                line = "⚽  الأهلي × " + arabicTeam(other) + " · " + fmt("EEEE", AR).format(when) + " "
                        + ampmText(fmt("HH:mm", Locale.US).format(when));
            }
        }
        final String text = line;
        post(() -> {
            matchText = text;
            render();
        });
    }

    // ---------------------------------------------------------------- cache

    private void loadCache() {
        try {
            String p = prefs.getString("prayers", null);
            String day = prefs.getString("prayersDay", "");
            if (p != null && today().equals(day)) applyPrayersNow(p, day);
        } catch (Exception e) {
            Log.w(TAG, "cache prayers", e);
        }
        try {
            String w = prefs.getString("weather", null);
            if (w != null && res.getBoolean(R.bool.show_weather)) showWeather(w);
        } catch (Exception ignored) {
        }
        try {
            String g = prefs.getString("gold", null);
            if (g != null && res.getBoolean(R.bool.show_gold)) showGold(g);
        } catch (Exception ignored) {
        }
        try {
            String b = prefs.getString("btc", null);
            if (b != null && res.getBoolean(R.bool.show_usd)) showBtc(b);
        } catch (Exception ignored) {
        }
        try {
            String m = prefs.getString("match", null);
            String id = prefs.getString("teamId", res.getString(R.string.team_id));
            if (m != null && !TextUtils.isEmpty(id) && res.getBoolean(R.bool.show_match)) showMatch(m, id);
        } catch (Exception ignored) {
        }
    }

    /** Same as applyPrayers, but synchronous, so the first frame already has the times. */
    private void applyPrayersNow(String body, String day) throws Exception {
        JSONObject data = new JSONObject(body).getJSONObject("data");
        JSONObject t = data.getJSONObject("timings");
        String[] out = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) out[i] = t.getString(KEYS[i]).trim().substring(0, 5);
        times = out;
        timesDay = day;
        JSONObject hj = data.getJSONObject("date").optJSONObject("hijri");
        if (hj != null) {
            JSONObject month = hj.optJSONObject("month");
            hijriDay = parseInt(hj.optString("day"));
            hijriYear = parseInt(hj.optString("year"));
            hijriMonth = month == null ? 0 : month.optInt("number", 0);
            hijriText = (hijriDay + " " + (month == null ? "" : month.optString("ar", "")) + " " + hijriYear + " هـ").trim();
        }
        updateRamadanText();
    }

    // ---------------------------------------------------------------- helpers

    private void post(Runnable r) {
        main.post(() -> {
            if (!destroyed) r.run();
        });
    }

    private static SimpleDateFormat fmt(String pattern, Locale locale) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, locale);
        f.setTimeZone(RIYADH);
        return f;
    }

    private static SimpleDateFormat utc(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static String today() {
        return fmt("dd-MM-yyyy", Locale.US).format(new Date());
    }

    private static int minutesNow() {
        Calendar c = Calendar.getInstance(RIYADH);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static int toMinutes(String hhmm, int fallback) {
        try {
            return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static final String[][] TEAMS = {
            {"Al-Nassr", "النصر"}, {"Al-Hilal", "الهلال"}, {"Al-Ittihad", "الاتحاد"}, {"Al-Shabab", "الشباب"},
            {"Al-Ettifaq", "الاتفاق"}, {"Al-Taawoun", "التعاون"}, {"Al-Fateh", "الفتح"}, {"Al-Fayha", "الفيحاء"},
            {"Al-Qadsiah", "القادسية"}, {"Al-Khaleej", "الخليج"}, {"Al-Riyadh", "الرياض"}, {"Al-Okhdood", "الأخدود"},
            {"Al-Akhdoud", "الأخدود"}, {"Damac", "ضمك"}, {"Al-Kholood", "الخلود"}, {"Al-Hazem", "الحزم"},
            {"Al-Najma", "النجمة"}, {"Abha", "أبها"}, {"Al-Wehda", "الوحدة"}, {"Al-Raed", "الرائد"},
            {"Al-Orobah", "العروبة"}, {"Al-Khalij", "الخليج"}, {"Neom", "نيوم"}, {"NEOM", "نيوم"},
            {"Al-Taawon", "التعاون"}, {"Al-Wahda", "الوحدة"}, {"Al-Gharafa", "الغرافة"}, {"Al-Sadd", "السد"},
    };

    private static String arabicTeam(String name) {
        for (String[] t : TEAMS) if (t[0].equalsIgnoreCase(name.trim())) return t[1];
        return name;
    }
}
