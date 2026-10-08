package ru.moscowmetro.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    private MetroData data;
    private Router router;
    private MetroMapView map;
    private AutoCompleteTextView fromField, toField;
    private MetroData.Entry from, to;
    private LinearLayout panel, routeBox;
    private MaxScroll routeScroll;

    /** ScrollView, который не выше заданной высоты (чтобы карта оставалась видна). */
    private static final class MaxScroll extends ScrollView {
        int maxHeight = Integer.MAX_VALUE;
        MaxScroll(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }
    private boolean dark;
    private float dp;
    private Colors col;
    private boolean settingText;

    /** Цвета интерфейса для светлой и тёмной темы. */
    private static final class Colors {
        int panel, field, text, secondary, divider, button, accent;

        static Colors of(boolean dark) {
            Colors c = new Colors();
            c.panel = dark ? 0xFF1E2024 : 0xFFFFFFFF;
            c.field = dark ? 0xFF2B2E33 : 0xFFF1F2F4;
            c.text = dark ? 0xFFECECEE : 0xFF1C1C1F;
            c.secondary = dark ? 0xFFA3A7AD : 0xFF5F6368;
            c.divider = dark ? 0xFF33363B : 0xFFE3E3E3;
            c.button = dark ? 0xEE24272B : 0xF2FFFFFF;
            c.accent = dark ? 0xFF6CB4F0 : 0xFF0066B3;
            return c;
        }
    }

    @Override protected void onCreate(Bundle state) {
        int mode = prefs().getInt("theme", THEME_SYSTEM);
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        dark = mode == THEME_DARK || (mode == THEME_SYSTEM && systemDark);
        setTheme(dark ? R.style.ThemeDark : R.style.ThemeLight);
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        col = Colors.of(dark);

        try {
            data = MetroData.load(this);
        } catch (Exception e) {
            TextView t = new TextView(this);
            t.setText("Не удалось загрузить схему: " + e);
            setContentView(t);
            return;
        }
        router = new Router(data);
        buildUi();
        setupWindow();

        if (state != null) {
            from = entryAt(state.getInt("from", -1));
            to = entryAt(state.getInt("to", -1));
            setFieldText(fromField, from);
            setFieldText(toField, to);
            updateRoute(false);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (data == null) return;
        out.putInt("from", from == null ? -1 : data.entries.indexOf(from));
        out.putInt("to", to == null ? -1 : data.entries.indexOf(to));
    }

    private MetroData.Entry entryAt(int i) { return i >= 0 && i < data.entries.size() ? data.entries.get(i) : null; }

    private SharedPreferences prefs() { return getSharedPreferences("settings", MODE_PRIVATE); }

    // ------------------------------------------------------------ интерфейс

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        map = new MetroMapView(this);
        map.setData(data);
        map.setPalette(MetroMapView.Palette.of(dark));
        map.setListener(this::onStationTap);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // кнопки в правом верхнем углу
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.addView(roundButton(R.drawable.ic_theme, "Тема оформления", v -> cycleTheme()));
        tools.addView(roundButton(R.drawable.ic_fit, "Показать всю схему", v -> {
            if (from != null && to != null && map != null) map.fitRoute(); else map.fitAll();
        }));
        tools.addView(roundButton(R.drawable.ic_info, "О приложении", v -> showAbout()));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tlp.setMargins(0, px(8), px(10), 0);
        root.addView(tools, tlp);
        toolsBox = tools;

        // нижняя панель: откуда / куда / маршрут
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(12), px(10), px(12), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(col.panel);
        bg.setCornerRadius(px(18));
        panel.setBackground(bg);
        panel.setElevation(px(8));

        routeScroll = new MaxScroll(this);
        routeBox = new LinearLayout(this);
        routeBox.setOrientation(LinearLayout.VERTICAL);
        routeScroll.addView(routeBox);
        routeScroll.setVisibility(View.GONE);
        panel.addView(routeScroll, new LinearLayout.LayoutParams(-1, -2));

        fromField = stationField("Откуда");
        toField = stationField("Куда");
        panel.addView(fieldRow(fromField, 0xFF1E8E3E, R.drawable.ic_close, "Очистить", v -> clearAll()));
        View gapView = new View(this);
        panel.addView(gapView, new LinearLayout.LayoutParams(-1, px(8)));
        panel.addView(fieldRow(toField, 0xFFD93025, R.drawable.ic_swap, "Поменять местами", v -> swap()));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        plp.setMargins(px(10), 0, px(10), px(10));
        root.addView(panel, plp);

        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> map.setInsets(topInset, root.getHeight() - t));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> routeScroll.maxHeight = (int) ((b - t) * 0.42f));
        this.root = root;
        setContentView(root);
    }

    private FrameLayout root;
    private LinearLayout toolsBox;
    private int topInset;

    private View roundButton(int icon, String desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setColorFilter(col.text);
        b.setContentDescription(desc);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(col.button);
        b.setBackground(g);
        b.setElevation(px(4));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(44), px(44));
        lp.setMargins(0, 0, 0, px(10));
        b.setLayoutParams(lp);
        return b;
    }

    private View fieldRow(AutoCompleteTextView field, int dotColor, int icon, String desc, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(dotColor);
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(12), px(12));
        dlp.setMargins(px(4), 0, px(10), 0);
        row.addView(dot, dlp);
        row.addView(field, new LinearLayout.LayoutParams(0, px(46), 1));
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setColorFilter(col.secondary);
        b.setContentDescription(desc);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(px(44), px(44)));
        return row;
    }

    private AutoCompleteTextView stationField(String hint) {
        final AutoCompleteTextView f = new AutoCompleteTextView(this);
        f.setHint(hint);
        f.setSingleLine(true);
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        f.setTextColor(col.text);
        f.setHintTextColor(col.secondary);
        f.setPadding(px(14), 0, px(14), 0);
        f.setThreshold(1);
        f.setImeOptions(EditorInfo.IME_ACTION_DONE);
        f.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        GradientDrawable g = new GradientDrawable();
        g.setColor(col.field);
        g.setCornerRadius(px(12));
        f.setBackground(g);
        GradientDrawable dd = new GradientDrawable();
        dd.setColor(col.panel);
        dd.setCornerRadius(px(12));
        f.setDropDownBackgroundDrawable(dd);
        final StationAdapter adapter = new StationAdapter();
        f.setAdapter(adapter);
        f.setOnItemClickListener((parent, view, pos, id) -> {
            MetroData.Entry e = adapter.getItem(pos);
            select(f == fromField, e);
        });
        f.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (settingText) return;
                boolean isFrom = f == fromField;
                MetroData.Entry cur = isFrom ? from : to;
                if (cur != null && !cur.name.contentEquals(s)) {
                    if (isFrom) from = null; else to = null;
                    updateRoute(false);
                }
            }
        });
        f.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                List<MetroData.Entry> res = data.search(f.getText().toString(), 2);
                if (res.size() >= 1 && (res.size() == 1 || res.get(0).norm.equals(MetroData.normalize(f.getText().toString())))) {
                    select(f == fromField, res.get(0));
                    f.dismissDropDown();
                }
                hideKeyboard();
                return true;
            }
            return false;
        });
        return f;
    }

    private final class StationAdapter extends BaseAdapter implements Filterable {
        private List<MetroData.Entry> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public MetroData.Entry getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row;
            TextView title, sub;
            if (v == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(px(16), px(9), px(16), px(9));
                title = new TextView(MainActivity.this);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                title.setTextColor(col.text);
                sub = new TextView(MainActivity.this);
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                sub.setTextColor(col.secondary);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title);
                row.addView(sub);
            } else {
                row = (LinearLayout) v;
                title = (TextView) row.getChildAt(0);
                sub = (TextView) row.getChildAt(1);
            }
            MetroData.Entry e = items.get(i);
            title.setText(dots(e.colors, e.name));
            sub.setText(e.lineNames);
            return row;
        }

        @Override public Filter getFilter() {
            return new Filter() {
                @Override protected FilterResults performFiltering(CharSequence q) {
                    FilterResults r = new FilterResults();
                    List<MetroData.Entry> list = q == null ? new ArrayList<>() : data.search(q.toString(), 40);
                    r.values = list;
                    r.count = list.size();
                    return r;
                }
                @SuppressWarnings("unchecked")
                @Override protected void publishResults(CharSequence q, FilterResults r) {
                    items = r.values == null ? new ArrayList<>() : (List<MetroData.Entry>) r.values;
                    notifyDataSetChanged();
                }
                @Override public CharSequence convertResultToString(Object o) { return ((MetroData.Entry) o).name; }
            };
        }
    }

    private CharSequence dots(int[] colors, String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<Integer> seen = new ArrayList<>();
        for (int c : colors) {
            if (seen.contains(c)) continue;
            seen.add(c);
            int start = sb.length();
            sb.append("●");
            sb.setSpan(new ForegroundColorSpan(c), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append("  ").append(text);
        return sb;
    }

    // ------------------------------------------------------------ окна и отступы

    private void setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                android.graphics.Insets bars = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = ins.getInsets(WindowInsets.Type.ime());
                applyInsets(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
            getWindow().setStatusBarColor(dark ? 0xFF121316 : 0xFFF6F6F3);
            getWindow().setNavigationBarColor(dark ? 0xFF121316 : Color.BLACK);
        }
    }

    private void applyInsets(int l, int t, int r, int b) {
        topInset = t;
        FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        plp.setMargins(px(10) + l, 0, px(10) + r, px(10) + b);
        panel.setLayoutParams(plp);
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) toolsBox.getLayoutParams();
        tlp.setMargins(0, px(8) + t, px(10) + r, 0);
        toolsBox.setLayoutParams(tlp);
    }

    // ------------------------------------------------------------ действия

    private void onStationTap(int station) {
        final MetroData.Entry e = data.entryOfStation[station];
        String[] items = {"Отсюда", "Сюда"};
        new AlertDialog.Builder(this)
            .setTitle(dots(e.colors, e.name))
            .setItems(items, (dlg, which) -> select(which == 0, e))
            .show();
    }

    private void select(boolean isFrom, MetroData.Entry e) {
        if (isFrom) from = e; else to = e;
        setFieldText(isFrom ? fromField : toField, e);
        hideKeyboard();
        if (isFrom && to == null) toField.requestFocus();
        else { fromField.clearFocus(); toField.clearFocus(); }
        updateRoute(true);
    }

    private void setFieldText(AutoCompleteTextView f, MetroData.Entry e) {
        settingText = true;
        f.setText(e == null ? "" : e.name, false);
        if (e != null) f.setSelection(f.getText().length());
        settingText = false;
    }

    private void swap() {
        MetroData.Entry t = from;
        from = to;
        to = t;
        setFieldText(fromField, from);
        setFieldText(toField, to);
        updateRoute(true);
    }

    private void clearAll() {
        from = null;
        to = null;
        setFieldText(fromField, null);
        setFieldText(toField, null);
        updateRoute(false);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void updateRoute(boolean animate) {
        Router.Route r = null;
        routeBox.removeAllViews();
        if (from != null && to != null) {
            if (from == to || from.stations[0] == to.stations[0]) {
                routeBox.addView(text("Выберите разные станции", 15, col.secondary, false));
            } else {
                r = router.find(from.stations, to.stations);
                if (r == null) routeBox.addView(text("Маршрут не найден", 15, col.secondary, false));
                else showRoute(r);
            }
            routeScroll.setVisibility(View.VISIBLE);
        } else {
            routeScroll.setVisibility(View.GONE);
        }
        routeScroll.scrollTo(0, 0);
        map.setSelection(from, to, r);
        if (animate) {
            final Router.Route route = r;
            panel.post(() -> panel.post(() -> {
                if (route != null) map.fitRoute();
                else if (from != null && to == null) map.focusStation(from.stations[0]);
                else if (to != null && from == null) map.focusStation(to.stations[0]);
            }));
        }
    }

    private void showRoute(Router.Route r) {
        int total = Math.round(r.minutes);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        TextView t = text("≈ " + formatMinutes(total), 22, col.text, true);
        head.addView(t);
        String sub = r.transfers == 0 ? "без пересадок" : r.transfers + " " + plural(r.transfers, "пересадка", "пересадки", "пересадок");
        sub += " · " + r.stops + " " + plural(r.stops, "перегон", "перегона", "перегонов");
        TextView s = text("   " + sub, 14, col.secondary, false);
        s.setPadding(0, 0, 0, px(3));
        head.addView(s);
        head.setPadding(px(4), 0, 0, px(6));
        routeBox.addView(head);

        for (Router.Step st : r.steps) {
            if (st.walk) {
                MetroData.Station a = data.stations[st.stations.get(0)];
                MetroData.Station b = data.stations[st.stations.get(st.stations.size() - 1)];
                String what = "Переход на «" + b.name + "», " + shortName(data.lines[b.line]);
                TextView w = text("🚶  " + what + " · " + formatMinutes(Math.round(st.minutes)), 14, col.secondary, false);
                w.setPadding(px(4), px(2), 0, px(8));
                routeBox.addView(w);
                continue;
            }
            MetroData.Line l = data.lines[st.line];
            MetroData.Station a = data.stations[st.stations.get(0)];
            MetroData.Station b = data.stations[st.stations.get(st.stations.size() - 1)];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            View bar = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(l.color);
            g.setCornerRadius(px(3));
            bar.setBackground(g);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(6), -1);
            blp.setMargins(px(3), px(2), px(12), px(2));
            row.addView(bar, blp);
            LinearLayout col2 = new LinearLayout(this);
            col2.setOrientation(LinearLayout.VERTICAL);
            TextView ln = text(shortName(l), 13, l.color == 0xFFADACAC ? col.secondary : l.color, true);
            if (dark && isDarkColor(l.color)) ln.setTextColor(lighten(l.color));
            col2.addView(ln);
            col2.addView(text(a.name + "  →  " + b.name, 15.5f, col.text, false));
            int n = st.stations.size() - 1;
            String info = n + " " + plural(n, "перегон", "перегона", "перегонов") + " · " + formatMinutes(Math.round(st.minutes));
            String dir = router.direction(st);
            if (dir != null && !dir.equals(b.name)) info += " · в сторону «" + dir + "»";
            col2.addView(text(info, 13, col.secondary, false));
            row.addView(col2, new LinearLayout.LayoutParams(0, -2, 1));
            row.setPadding(0, 0, 0, px(8));
            routeBox.addView(row);
        }
        View div = new View(this);
        div.setBackgroundColor(col.divider);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Math.max(1, px(1) / 2));
        dlp.setMargins(0, px(2), 0, px(10));
        routeBox.addView(div, dlp);
    }

    private static boolean isDarkColor(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 110;
    }

    private static int lighten(int c) {
        return Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2);
    }

    private static String shortName(MetroData.Line l) {
        if (l.type.equals("mcd")) {
            int sp = l.name.indexOf(' ');
            return sp > 0 ? l.name.substring(0, sp) : l.name;
        }
        if (l.type.equals("mcc")) return "МЦК";
        return l.name + " линия";
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static String formatMinutes(int m) {
        if (m < 60) return m + " мин";
        return (m / 60) + " ч " + (m % 60) + " мин";
    }

    static String plural(int n, String one, String few, String many) {
        int n10 = n % 10, n100 = n % 100;
        if (n10 == 1 && n100 != 11) return one;
        if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return few;
        return many;
    }

    private void cycleTheme() {
        int mode = (prefs().getInt("theme", THEME_SYSTEM) + 1) % 3;
        prefs().edit().putInt("theme", mode).apply();
        String[] names = {"Тема как в системе", "Светлая тема", "Тёмная тема"};
        Toast.makeText(this, names[mode], Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void showAbout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(22), px(8), px(22), px(4));
        String upd = data.updated;
        String[] p = upd.split("-");
        if (p.length == 3) upd = p[2] + "." + p[1] + "." + p[0];
        box.addView(text("Схема актуальна на " + upd + "\nВерсия " + data.version + "\nРаботает без интернета.", 14.5f, col.text, false));
        TextView src = text("\nИсточник: " + data.source, 12.5f, col.secondary, false);
        box.addView(src);
        TextView lh = text("\nЛинии", 14.5f, col.text, true);
        box.addView(lh);
        for (MetroData.Line l : data.lines) {
            box.addView(text("", 2, col.text, false));
            TextView t = new TextView(this);
            t.setText(dots(new int[]{l.color}, l.type.equals("metro") ? l.id + "  " + l.name : l.name));
            t.setTextColor(col.text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            box.addView(t);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setView(sv)
            .setPositiveButton("OK", null)
            .show();
    }

    private int px(float v) { return Math.round(v * dp); }

}
