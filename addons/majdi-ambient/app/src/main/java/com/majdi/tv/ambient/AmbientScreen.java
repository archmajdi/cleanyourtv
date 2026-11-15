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
import android.text.format.DateFormat;
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
 * A calm, mostly empty black screen. Four small corners and one line of dhikr:
 * top right   clock, Gregorian and Hijri date
 * top left    weather, Al-Ahli's next match
 * bottom right next prayer with a countdown, today's five prayers
 * bottom left gold and the dollar in riyals
 * The whole layout drifts a few pixels every minute so nothing burns into the panel.
 */
final class AmbientScreen {
    private static final String TAG = "MajdiAmbient";
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long RETRY = 5 * MIN;

    private static final String[] KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] NAMES = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final Locale AR = Locale.forLanguageTag("ar-u-nu-latn");
    private static final TimeZone RIYADH = TimeZone.getTimeZone("Asia/Riyadh");

    private final Context ctx;
    private final Resources res;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();
    private final float density;
    private final int alpha;

    private final FrameLayout root;
    private final FrameLayout content;
    private final GlowView glow;
    private TextView gold21, clock, date, hijriView, weather, weatherTemp, match, matchWhen, nextPrayer, gold, usd, dhikr;
    private TextView[] prayerName, prayerTime;
    private LinearLayout weatherBox, matchBox, prayerBox, goldBox;

    private volatile boolean running;
    private boolean destroyed;

    // Data, filled from the cache first, then from the network.
    private String[] times; // HH:mm for KEYS
    private String timesDay; // dd-MM-yyyy the times are for
    private String hijri = "";
    private long weatherAt, goldAt, usdAt, matchAt, prayersAt;
    private int dhikrIndex;

    AmbientScreen(Context context) {
        ctx = context;
        res = context.getResources();
        prefs = context.getSharedPreferences("ambient", Context.MODE_PRIVATE);
        density = res.getDisplayMetrics().density;
        alpha = Math.max(20, Math.min(100, res.getInteger(R.integer.text_alpha)));
        dhikrIndex = rnd.nextInt(100);

        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        glow = new GlowView(ctx);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(ctx);
        int pad = dp(40);
        content.setPadding(pad, dp(32), pad, dp(32));
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
        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

        // Top right: the time and both dates.
        LinearLayout tr = box(Gravity.RIGHT);
        clock = text(54, 100, light, Gravity.RIGHT);
        date = text(19, 75, regular, Gravity.RIGHT);
        hijriView = text(16, 60, regular, Gravity.RIGHT);
        tr.addView(clock);
        tr.addView(date, gap(8));
        tr.addView(hijriView, gap(5));
        content.addView(tr, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));

        // Top left: weather, and Al-Ahli's next match under it.
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.LEFT);
        weatherBox = box(Gravity.LEFT);
        weatherTemp = text(44, 100, light, Gravity.LEFT);
        weather = text(18, 70, regular, Gravity.LEFT);
        weatherBox.addView(weatherTemp);
        weatherBox.addView(weather, gap(6));
        left.addView(weatherBox);
        matchBox = box(Gravity.LEFT);
        match = text(20, 85, regular, Gravity.LEFT);
        matchWhen = text(16, 65, regular, Gravity.LEFT);
        matchBox.addView(match);
        matchBox.addView(matchWhen, gap(6));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = dp(14);
        left.addView(matchBox, ml);
        content.addView(left, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));

        // Bottom right: the next prayer, then the five prayers as columns (Fajr on the right).
        prayerBox = box(Gravity.RIGHT);
        nextPrayer = text(20, 90, regular, Gravity.RIGHT);
        prayerBox.addView(nextPrayer);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        prayerName = new TextView[5];
        prayerTime = new TextView[5];
        for (int i = 0; i < 5; i++) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            prayerName[i] = text(15, 60, regular, Gravity.CENTER_HORIZONTAL);
            prayerTime[i] = text(21, 80, regular, Gravity.CENTER_HORIZONTAL);
            prayerName[i].setSingleLine(true);
            prayerTime[i].setSingleLine(true);
            cell.addView(prayerName[i]);
            cell.addView(prayerTime[i], gap(6));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) cl.setMarginStart(dp(26));
            row.addView(cell, cl);
        }
        prayerBox.addView(row, gap(12));
        content.addView(prayerBox, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT));

        // Bottom left: gold and the dollar.
        goldBox = box(Gravity.LEFT);
        gold = text(18, 85, regular, Gravity.LEFT);
        gold21 = text(18, 85, regular, Gravity.LEFT);
        usd = text(16, 65, regular, Gravity.LEFT);
        goldBox.addView(gold);
        goldBox.addView(gold21, gap(8));
        goldBox.addView(usd, gap(8));
        content.addView(goldBox, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT));

        // The dhikr, in the middle of the screen.
        dhikr = text(24, 80, light, Gravity.CENTER);
        dhikr.setMaxWidth((int) (res.getDisplayMetrics().widthPixels * 0.50f));
        dhikr.setLineSpacing(0, 1.3f);
        dhikr.setBackground(frame());
        dhikr.setPadding(dp(30), dp(18), dp(30), dp(18));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        dl.topMargin = dp(10);
        content.addView(dhikr, dl);

        weatherBox.setVisibility(View.GONE);
        matchBox.setVisibility(View.GONE);
        goldBox.setVisibility(View.GONE);
        gold.setVisibility(View.GONE);
        gold21.setVisibility(View.GONE);
        usd.setVisibility(View.GONE);
        prayerBox.setVisibility(View.GONE);
        hijriView.setVisibility(View.GONE);
        if (!res.getBoolean(R.bool.show_adhkar)) dhikr.setVisibility(View.GONE);
    }

    private LinearLayout box(int gravity) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(gravity);
        l.setBackground(frame());
        l.setPadding(dp(22), dp(16), dp(22), dp(16));
        return l;
    }

    /** A simple frame: a thin, faint white line around a slightly darker glass, with round corners. */
    private GradientDrawable frame() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(16));
        d.setColor(0x33000000);
        d.setStroke(Math.max(1, dp(1)), 0x2EFFFFFF);
        return d;
    }

    private LinearLayout.LayoutParams gap(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView text(float sp, int strength, Typeface face, int gravity) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        int a = Math.round(255f * alpha / 100f * strength / 100f);
        t.setTextColor(Color.argb(a, 255, 255, 255));
        t.setTypeface(face);
        t.setGravity(gravity);
        t.setIncludeFontPadding(false);
        t.setSingleLine(gravity != Gravity.CENTER);
        t.setShadowLayer(dp(8), 0, 0, 0xAA000000);
        return t;
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
        float r = dp(14);
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
        boolean morning = isMorning();
        String[] timed = morning ? Adhkar.MORNING : Adhkar.EVENING;
        List<String> all = new ArrayList<>();
        for (String s : timed) all.add(s);
        for (String s : Adhkar.GENERAL) all.add(s);
        dhikrIndex = (dhikrIndex + 1) % all.size();
        return all.get(dhikrIndex);
    }

    /** From Fajr to Asr; without prayer times, 4:00 to 15:00. */
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
        boolean h24 = DateFormat.is24HourFormat(ctx);
        clock.setText(fmt(h24 ? "HH:mm" : "h:mm", Locale.US).format(now));
        date.setText(fmt("EEEE d MMMM", AR).format(now));
        if (!TextUtils.isEmpty(hijri)) {
            hijriView.setText(hijri);
            hijriView.setVisibility(View.VISIBLE);
        }
        renderPrayers(h24);
        renderSky();
    }

    // Dawn, day, golden afternoon, sunset, night: the glow's colours follow the prayer times.
    private static final int[] DAWN = {0xFF3A4FA0, 0xFF9A4A72, 0xFFB8744A};
    private static final int[] DAY = {0xFF0E6A88, 0xFF1F4FA8, 0xFF1F7A6A};
    private static final int[] GOLDEN = {0xFFB0682E, 0xFF8E3A48, 0xFF4E3A86};
    private static final int[] DUSK = {0xFF3B2B7A, 0xFF7A3468, 0xFF1E4A7A};
    private static final int[] NIGHT = {0xFF1E2A6E, 0xFF4A2A7A, 0xFF0F4C5C};

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

    private static final int[] PRAYERS = {0, 2, 3, 4, 5}; // sunrise is not a prayer

    private void renderPrayers(boolean h24) {
        if (!res.getBoolean(R.bool.show_prayers) || times == null || !today().equals(timesDay)) {
            prayerBox.setVisibility(View.GONE);
            return;
        }
        int now = minutesNow();
        int next = -1;
        for (int i : PRAYERS) {
            if (toMinutes(times[i], -1) > now) {
                next = i;
                break;
            }
        }
        if (next >= 0) {
            int left = toMinutes(times[next], now) - now;
            String in = left >= 60 ? (left / 60) + " س " + (left % 60) + " د" : left + " د";
            nextPrayer.setText(NAMES[next] + " بعد " + in);
        } else {
            nextPrayer.setText("الفجر غدًا  " + clockText(times[0], h24));
        }
        int highlight = next >= 0 ? next : 0;
        int bright = Math.round(255f * alpha / 100f);
        int dim = Math.round(255f * alpha / 100f * 0.55f);
        for (int c = 0; c < PRAYERS.length; c++) {
            int i = PRAYERS[c];
            boolean on = i == highlight;
            prayerName[c].setText(NAMES[i]);
            prayerTime[c].setText(clockText(times[i], h24));
            prayerName[c].setTextColor(Color.argb(on ? bright : dim, 255, 255, 255));
            prayerTime[c].setTextColor(Color.argb(on ? bright : dim, 255, 255, 255));
        }
        prayerBox.setVisibility(View.VISIBLE);
    }

    private static String clockText(String hhmm, boolean h24) {
        if (h24) return hhmm;
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            String m = hhmm.substring(3, 5);
            h = h % 12;
            if (h == 0) h = 12;
            return h + ":" + m;
        } catch (Exception e) {
            return hhmm;
        }
    }

    // ---------------------------------------------------------------- refresh

    private void refreshIfDue() {
        long now = System.currentTimeMillis();
        if (res.getBoolean(R.bool.show_prayers) && (!today().equals(timesDay) || now - prayersAt > 6 * HOUR)) {
            prayersAt = now - 6 * HOUR + RETRY;
            io.execute(this::fetchPrayers);
        }
        if (res.getBoolean(R.bool.show_weather) && now - weatherAt > 30 * MIN) {
            weatherAt = now - 30 * MIN + RETRY;
            io.execute(this::fetchWeather);
        }
        if (res.getBoolean(R.bool.show_gold) && now - goldAt > HOUR) {
            goldAt = now - HOUR + RETRY;
            io.execute(this::fetchGold);
        }
        if (res.getBoolean(R.bool.show_usd) && now - usdAt > 6 * HOUR) {
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
            parsePrayers(body, day);
            prefs.edit().putString("prayers", body).putString("prayersDay", day).apply();
            prayersAt = System.currentTimeMillis();
            post(this::render);
        } catch (Exception e) {
            Log.w(TAG, "prayers", e);
        }
    }

    private void parsePrayers(String body, String day) throws Exception {
        JSONObject data = new JSONObject(body).getJSONObject("data");
        JSONObject t = data.getJSONObject("timings");
        String[] out = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) out[i] = t.getString(KEYS[i]).trim().substring(0, 5);
        String h = "";
        JSONObject date = data.optJSONObject("date");
        JSONObject hj = date == null ? null : date.optJSONObject("hijri");
        if (hj != null) {
            JSONObject month = hj.optJSONObject("month");
            String mName = month == null ? "" : month.optString("ar", "");
            h = trimZero(hj.optString("day", "")) + " " + mName + " " + hj.optString("year", "") + " هـ";
        }
        final String hijriText = h.trim();
        final String[] parsed = out;
        post(() -> {
            times = parsed;
            timesDay = day;
            hijri = hijriText;
        });
    }

    private void fetchWeather() {
        try {
            String body = Net.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat() + "&longitude=" + lon()
                    + "&current=temperature_2m,weather_code&timezone=auto");
            showWeather(body);
            prefs.edit().putString("weather", body).apply();
            weatherAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "weather", e);
        }
    }

    private void showWeather(String body) throws Exception {
        JSONObject cur = new JSONObject(body).getJSONObject("current");
        long temp = Math.round(cur.getDouble("temperature_2m"));
        String sky = sky(cur.optInt("weather_code", -1));
        final String tempText = temp + "°";
        final String line = (sky.isEmpty() ? "" : sky + "  ·  ") + res.getString(R.string.place);
        post(() -> {
            weatherTemp.setText(tempText);
            weather.setText(line);
            weatherBox.setVisibility(View.VISIBLE);
        });
    }

    private static String sky(int code) {
        if (code == 0) return "صحو";
        if (code == 1) return "صحو غالبًا";
        if (code == 2) return "غائم جزئيًا";
        if (code == 3) return "غائم";
        if (code == 45 || code == 48) return "ضباب";
        if (code >= 51 && code <= 57) return "رذاذ";
        if (code >= 61 && code <= 67) return "مطر";
        if (code >= 71 && code <= 77) return "ثلج";
        if (code >= 80 && code <= 82) return "زخات مطر";
        if (code >= 95) return "عاصفة رعدية";
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
        long gram24 = Math.round(usdOunce * rate / 31.1034768);
        long gram21 = Math.round(usdOunce * rate / 31.1034768 * 21.0 / 24.0);
        final String line24 = "ذهب عيار 24  ·  " + gram24 + " ر.س";
        final String line21 = "ذهب عيار 21  ·  " + gram21 + " ر.س";
        post(() -> {
            gold.setText(line24);
            gold21.setText(line21);
            gold.setVisibility(View.VISIBLE);
            gold21.setVisibility(View.VISIBLE);
            goldBox.setVisibility(View.VISIBLE);
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
        final String line = "الدولار  " + String.format(Locale.US, "%.2f", sar) + "  ر.س";
        post(() -> {
            usd.setText(line);
            usd.setVisibility(View.VISIBLE);
            goldBox.setVisibility(View.VISIBLE);
        });
    }

    private void fetchMatch() {
        try {
            String id = prefs.getString("teamId", res.getString(R.string.team_id));
            if (TextUtils.isEmpty(id)) {
                String q = java.net.URLEncoder.encode(res.getString(R.string.team_query), "UTF-8").replace("+", "%20");
                JSONArray teams = new JSONObject(Net.get(
                        "https://www.thesportsdb.com/api/v1/json/123/searchteams.php?t=" + q)).optJSONArray("teams");
                if (teams != null) {
                    for (int i = 0; i < teams.length(); i++) {
                        JSONObject t = teams.getJSONObject(i);
                        if (t.optString("strCountry").contains("Saudi") && "Soccer".equals(t.optString("strSport"))) {
                            id = t.optString("idTeam");
                            break;
                        }
                    }
                }
                if (TextUtils.isEmpty(id)) return;
                prefs.edit().putString("teamId", id).apply();
            }
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
        String line = null;
        long now = System.currentTimeMillis();
        if (events != null) {
            for (int i = 0; i < events.length() && line == null; i++) {
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
                SimpleDateFormat day = fmt("EEEE", AR);
                SimpleDateFormat hm = fmt(DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm a", AR);
                line = "الأهلي × " + arabicTeam(other) + "\n" + day.format(when) + "  " + hm.format(when);
            }
        }
        final String text = line;
        post(() -> {
            if (text == null) {
                matchBox.setVisibility(View.GONE);
            } else {
                String[] parts = text.split("\n", 2);
                match.setText(parts[0]);
                matchWhen.setText(parts.length > 1 ? parts[1] : "");
                matchBox.setVisibility(View.VISIBLE);
            }
        });
    }

    // ---------------------------------------------------------------- cache

    private void loadCache() {
        try {
            String p = prefs.getString("prayers", null);
            String day = prefs.getString("prayersDay", "");
            if (p != null && today().equals(day)) {
                JSONObject data = new JSONObject(p).getJSONObject("data");
                JSONObject t = data.getJSONObject("timings");
                String[] out = new String[KEYS.length];
                for (int i = 0; i < KEYS.length; i++) out[i] = t.getString(KEYS[i]).trim().substring(0, 5);
                times = out;
                timesDay = day;
                JSONObject hj = data.getJSONObject("date").optJSONObject("hijri");
                if (hj != null) {
                    JSONObject month = hj.optJSONObject("month");
                    hijri = (trimZero(hj.optString("day", "")) + " " + (month == null ? "" : month.optString("ar", ""))
                            + " " + hj.optString("year", "") + " هـ").trim();
                }
            }
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
        if (prefs.contains("sar") && res.getBoolean(R.bool.show_usd)) showUsd(prefs.getFloat("sar", 3.75f));
        try {
            String m = prefs.getString("match", null);
            String id = prefs.getString("teamId", "");
            if (m != null && !TextUtils.isEmpty(id) && res.getBoolean(R.bool.show_match)) showMatch(m, id);
        } catch (Exception ignored) {
        }
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

    private static String trimZero(String s) {
        while (s.length() > 1 && s.charAt(0) == '0') s = s.substring(1);
        return s;
    }
}
