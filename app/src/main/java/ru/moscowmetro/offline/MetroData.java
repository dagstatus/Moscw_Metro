package ru.moscowmetro.offline;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Схема метро из assets/metro.json (файл собирает tools/Build.java из таблиц в папке data/). */
final class MetroData {

    static final class Line {
        String id, name, type;
        int color;
        boolean ring;
        float wait;
        int[][] paths;

        boolean isMetro() { return "metro".equals(type); }
    }

    static final class Station {
        int idx, line, group;
        String name;
        float x, y;
    }

    /** Пункт поиска: станции с одним названием в одном пересадочном узле. */
    static final class Entry {
        String name, norm;
        int[] stations;
        int[] colors;
        String lineNames;
    }

    Line[] lines;
    Station[] stations;
    int[] edgeA, edgeB, trA, trB;
    float[] edgeMin, trMin;
    String updated, source, version;
    final List<Entry> entries = new ArrayList<>();
    Entry[] entryOfStation;
    final RectF bounds = new RectF(), coreBounds = new RectF();
    int groupCount;

    static MetroData load(Context ctx) throws Exception {
        InputStream in = ctx.getAssets().open("metro.json");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) bos.write(buf, 0, n);
        in.close();
        JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        MetroData d = new MetroData();
        d.updated = root.optString("updated");
        d.source = root.optString("source");
        d.version = root.optString("version");

        JSONArray ls = root.getJSONArray("lines");
        d.lines = new Line[ls.length()];
        Map<String, Integer> lineIdx = new LinkedHashMap<>();
        for (int i = 0; i < ls.length(); i++) {
            JSONObject o = ls.getJSONObject(i);
            Line l = new Line();
            l.id = o.getString("id");
            l.name = o.getString("name");
            l.type = o.optString("type", "metro");
            l.color = Color.parseColor(o.getString("color"));
            l.ring = o.optBoolean("ring");
            l.wait = (float) o.optDouble("wait", 2);
            JSONArray ps = o.getJSONArray("paths");
            l.paths = new int[ps.length()][];
            for (int p = 0; p < ps.length(); p++) {
                JSONArray a = ps.getJSONArray(p);
                l.paths[p] = new int[a.length()];
                for (int k = 0; k < a.length(); k++) l.paths[p][k] = a.getInt(k);
            }
            d.lines[i] = l;
            lineIdx.put(l.id, i);
        }

        JSONArray ss = root.getJSONArray("stations");
        d.stations = new Station[ss.length()];
        d.bounds.set(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        d.coreBounds.set(d.bounds);
        for (int i = 0; i < ss.length(); i++) {
            JSONObject o = ss.getJSONObject(i);
            Station s = new Station();
            s.idx = i;
            Integer li = lineIdx.get(o.getString("l"));
            s.line = li == null ? 0 : li;
            s.name = o.getString("n");
            s.x = (float) o.getDouble("x");
            s.y = (float) o.getDouble("y");
            d.stations[i] = s;
            grow(d.bounds, s.x, s.y);
            if (!"mcd".equals(d.lines[s.line].type)) grow(d.coreBounds, s.x, s.y);
        }

        JSONArray es = root.getJSONArray("edges");
        d.edgeA = new int[es.length()]; d.edgeB = new int[es.length()]; d.edgeMin = new float[es.length()];
        for (int i = 0; i < es.length(); i++) {
            JSONArray e = es.getJSONArray(i);
            d.edgeA[i] = e.getInt(0); d.edgeB[i] = e.getInt(1); d.edgeMin[i] = (float) e.getDouble(2);
        }
        JSONArray ts = root.getJSONArray("transfers");
        d.trA = new int[ts.length()]; d.trB = new int[ts.length()]; d.trMin = new float[ts.length()];
        for (int i = 0; i < ts.length(); i++) {
            JSONArray e = ts.getJSONArray(i);
            d.trA[i] = e.getInt(0); d.trB[i] = e.getInt(1); d.trMin[i] = (float) e.getDouble(2);
        }
        d.buildGroups();
        d.buildEntries();
        return d;
    }

    private static void grow(RectF r, float x, float y) {
        r.left = Math.min(r.left, x); r.top = Math.min(r.top, y);
        r.right = Math.max(r.right, x); r.bottom = Math.max(r.bottom, y);
    }

    private void buildGroups() {
        int n = stations.length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < trA.length; i++) {
            int a = find(parent, trA[i]), b = find(parent, trB[i]);
            if (a != b) parent[a] = b;
        }
        int[] map = new int[n];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            if (map[r] < 0) map[r] = groupCount++;
            stations[i].group = map[r];
        }
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    private void buildEntries() {
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (Station s : stations) {
            String k = s.group + "|" + normalize(s.name);
            List<Integer> l = byKey.get(k);
            if (l == null) byKey.put(k, l = new ArrayList<>());
            l.add(s.idx);
        }
        entryOfStation = new Entry[stations.length];
        for (List<Integer> idxs : byKey.values()) {
            Entry e = new Entry();
            e.name = stations[idxs.get(0)].name;
            e.norm = normalize(e.name);
            e.stations = new int[idxs.size()];
            e.colors = new int[idxs.size()];
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < idxs.size(); i++) {
                Station s = stations[idxs.get(i)];
                e.stations[i] = s.idx;
                e.colors[i] = lines[s.line].color;
                if (i > 0) names.append(", ");
                names.append(lines[s.line].name);
                entryOfStation[s.idx] = e;
            }
            e.lineNames = names.toString();
            entries.add(e);
        }
        final Collator c = Collator.getInstance(new Locale("ru"));
        Collections.sort(entries, (a, b) -> c.compare(a.name, b.name));
    }

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('-', ' ').replace('.', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Поиск по началу названия, началу любого слова, затем по вхождению. */
    List<Entry> search(String query, int limit) {
        String q = normalize(query);
        List<Entry> a = new ArrayList<>(), b = new ArrayList<>(), c = new ArrayList<>();
        if (q.isEmpty()) return a;
        for (Entry e : entries) {
            if (e.norm.startsWith(q)) a.add(e);
            else if (e.norm.contains(" " + q)) b.add(e);
            else if (e.norm.contains(q)) c.add(e);
        }
        a.addAll(b);
        a.addAll(c);
        return a.size() > limit ? new ArrayList<>(a.subList(0, limit)) : a;
    }

    Entry findEntry(String name) {
        if (name == null) return null;
        String n = normalize(name);
        for (Entry e : entries) if (e.norm.equals(n)) return e;
        return null;
    }
}
