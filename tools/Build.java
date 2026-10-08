import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * Инструмент обновления приложения «Метро Москвы».
 *
 * Запуск (нужен JDK 17+, например тот, что идёт с Android Studio):
 *   java tools/Build.java check     — проверить файлы в data/
 *   java tools/Build.java preview   — проверить данные и открыть схему в браузере (dist/preview.html)
 *   java tools/Build.java apk       — собрать тестовый APK, не меняя версию
 *   java tools/Build.java release   — поднять версию и собрать подписанный APK для выпуска
 *
 * Для сборки APK нужен Android SDK (ставится вместе с Android Studio): build-tools и platforms;android-34 или новее.
 * Путь к SDK берётся из ANDROID_HOME / ANDROID_SDK_ROOT, local.properties (sdk.dir) или стандартной папки.
 */
public class Build {

    static Path ROOT;
    static final List<String> warnings = new ArrayList<>();
    static final List<String> errors = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        ROOT = findRoot();
        String cmd = args.length > 0 ? args[0] : "help";
        switch (cmd) {
            case "check": {
                Model m = loadAndValidate();
                report(m);
                break;
            }
            case "preview": {
                Model m = loadAndValidate();
                report(m);
                if (!errors.isEmpty()) System.exit(1);
                writeAssets(m, readVersion());
                Path p = writePreview(m);
                System.out.println("Схема: " + p.toAbsolutePath());
                openInBrowser(p);
                break;
            }
            case "apk":
            case "release": {
                Model m = loadAndValidate();
                report(m);
                if (!errors.isEmpty()) System.exit(1);
                Version v = readVersion();
                if (cmd.equals("release")) {
                    v.code += 1;
                    v.name = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) + "." + v.code;
                }
                writeAssets(m, v);
                writePreview(m);
                Path apk = buildApk(v, cmd.equals("release"));
                if (cmd.equals("release")) writeVersion(v);
                System.out.println();
                System.out.println("Готово: " + apk.toAbsolutePath());
                System.out.println("Версия " + v.name + " (versionCode " + v.code + ")");
                break;
            }
            default:
                System.out.println("Использование: java tools/Build.java check | preview | apk | release");
        }
    }

    // ------------------------------------------------------------------ model

    static class Line {
        String id, name, color, type, emoji;
        boolean ring;
        double wait;
        List<Integer> stations = new ArrayList<>();
    }

    static class Station {
        int idx;
        String line, name, prev, row;
        double lat, lon, minutes = -1;
        double x = Double.NaN, y = Double.NaN;
        boolean manualXY;
    }

    static class Edge {
        int a, b;
        double min;
        Edge(int a, int b, double min) { this.a = a; this.b = b; this.min = min; }
    }

    static class Model {
        Map<String, Line> lines = new LinkedHashMap<>();
        List<Station> stations = new ArrayList<>();
        Map<String, Integer> byKey = new HashMap<>();
        List<Edge> edges = new ArrayList<>();
        List<Edge> transfers = new ArrayList<>();
        Map<String, List<List<Integer>>> paths = new LinkedHashMap<>();
        Properties info = new Properties();
    }

    static String key(String line, String name) { return line.trim() + "|" + norm(name); }

    static String norm(String s) { return s.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " "); }

    static Model loadAndValidate() throws IOException {
        Model m = new Model();
        Path info = ROOT.resolve("data/info.properties");
        if (Files.exists(info)) try (Reader r = Files.newBufferedReader(info, StandardCharsets.UTF_8)) { m.info.load(r); }

        // lines.csv
        for (Map<String, String> r : readCsv(ROOT.resolve("data/lines.csv"))) {
            Line l = new Line();
            l.id = req(r, "id");
            l.name = req(r, "name");
            l.color = r.getOrDefault("color", "").trim();
            l.type = r.getOrDefault("type", "metro").trim().toLowerCase(Locale.ROOT);
            l.ring = isTrue(r.get("ring"));
            l.emoji = r.getOrDefault("emoji", "").trim();
            l.wait = parseD(r.get("wait"), 2, "lines.csv " + r.get("_row") + " wait");
            if (!l.color.matches("#[0-9A-Fa-f]{6}")) errors.add("lines.csv строка " + r.get("_row") + ": цвет должен быть вида #RRGGBB, сейчас «" + l.color + "»");
            if (!Set.of("metro", "mcc", "mcd").contains(l.type)) errors.add("lines.csv строка " + r.get("_row") + ": type должен быть metro, mcc или mcd");
            if (m.lines.containsKey(l.id)) errors.add("lines.csv: линия " + l.id + " указана дважды");
            m.lines.put(l.id, l);
        }

        // stations.csv
        for (Map<String, String> r : readCsv(ROOT.resolve("data/stations.csv"))) {
            Station s = new Station();
            s.row = "stations.csv строка " + r.get("_row");
            s.line = req(r, "line");
            s.name = req(r, "name").trim();
            s.lat = parseD(r.get("lat"), Double.NaN, s.row + " lat");
            s.lon = parseD(r.get("lon"), Double.NaN, s.row + " lon");
            s.prev = r.getOrDefault("prev", "").trim();
            s.minutes = parseD(r.get("minutes"), -1, s.row + " minutes");
            s.x = parseD(r.get("x"), Double.NaN, s.row + " x");
            s.y = parseD(r.get("y"), Double.NaN, s.row + " y");
            s.manualXY = !Double.isNaN(s.x) && !Double.isNaN(s.y);
            Line l = m.lines.get(s.line);
            if (l == null) { errors.add(s.row + ": линии «" + s.line + "» нет в lines.csv"); continue; }
            if (s.name.isEmpty()) { errors.add(s.row + ": пустое название"); continue; }
            if (Double.isNaN(s.lat) || Double.isNaN(s.lon)) errors.add(s.row + " (" + s.name + "): не заданы координаты lat/lon");
            else if (s.lat < 54.8 || s.lat > 56.8 || s.lon < 35.8 || s.lon > 39.5) errors.add(s.row + " (" + s.name + "): координаты вне Московского региона: " + s.lat + ", " + s.lon);
            String k = key(s.line, s.name);
            if (m.byKey.containsKey(k)) { errors.add(s.row + ": станция «" + s.name + "» на линии " + s.line + " уже есть"); continue; }
            s.idx = m.stations.size();
            m.byKey.put(k, s.idx);
            m.stations.add(s);
            l.stations.add(s.idx);
        }

        // edges & drawing paths
        for (Line l : m.lines.values()) {
            List<List<Integer>> paths = new ArrayList<>();
            List<Integer> cur = null;
            Integer prevIdx = null;
            for (int idx : l.stations) {
                Station s = m.stations.get(idx);
                Integer from = null;
                if (s.prev.equals("-")) from = null;
                else if (!s.prev.isEmpty()) {
                    from = m.byKey.get(key(l.id, s.prev));
                    if (from == null) errors.add(s.row + ": в поле prev указана «" + s.prev + "», такой станции нет на линии " + l.id);
                } else from = prevIdx;
                if (from != null) {
                    Station f = m.stations.get(from);
                    double min = s.minutes > 0 ? s.minutes : rideMinutes(f, s, l.type);
                    m.edges.add(new Edge(from, idx, min));
                    if (cur != null && cur.get(cur.size() - 1) == (int) from) cur.add(idx);
                    else { cur = new ArrayList<>(List.of(from, idx)); paths.add(cur); }
                    double d = distKm(f, s);
                    if (d > (l.type.equals("mcd") ? 15 : 7)) warnings.add(s.row + ": между «" + f.name + "» и «" + s.name + "» " + String.format(Locale.ROOT, "%.1f", d) + " км — проверьте координаты или порядок");
                } else {
                    cur = null;
                }
                prevIdx = idx;
            }
            if (l.ring && l.stations.size() > 2) {
                int first = l.stations.get(0), last = l.stations.get(l.stations.size() - 1);
                m.edges.add(new Edge(last, first, rideMinutes(m.stations.get(last), m.stations.get(first), l.type)));
                if (paths.size() == 1 && paths.get(0).get(0) == first) paths.get(0).add(first);
                else paths.add(new ArrayList<>(List.of(last, first)));
            }
            if (l.stations.isEmpty()) warnings.add("Линия " + l.id + " («" + l.name + "») без станций — на схеме её не будет");
            m.paths.put(l.id, paths);
        }

        // transfers.csv
        Set<String> seen = new HashSet<>();
        for (Map<String, String> r : readCsv(ROOT.resolve("data/transfers.csv"))) {
            String row = "transfers.csv строка " + r.get("_row");
            Integer a = m.byKey.get(key(req(r, "line1"), req(r, "station1")));
            Integer b = m.byKey.get(key(req(r, "line2"), req(r, "station2")));
            if (a == null) { errors.add(row + ": нет станции «" + r.get("station1") + "» на линии " + r.get("line1")); continue; }
            if (b == null) { errors.add(row + ": нет станции «" + r.get("station2") + "» на линии " + r.get("line2")); continue; }
            if (a.equals(b)) { errors.add(row + ": переход станции самой на себя"); continue; }
            String k = Math.min(a, b) + "-" + Math.max(a, b);
            if (!seen.add(k)) { warnings.add(row + ": такой переход уже есть"); continue; }
            double min = parseD(r.get("minutes"), 4, row + " minutes");
            m.transfers.add(new Edge(a, b, min));
            double d = distKm(m.stations.get(a), m.stations.get(b));
            if (d > 1.5) warnings.add(row + ": станции перехода в " + String.format(Locale.ROOT, "%.1f", d) + " км друг от друга — проверьте");
        }

        // connectivity
        if (!m.stations.isEmpty()) {
            List<List<Integer>> adj = new ArrayList<>();
            for (int i = 0; i < m.stations.size(); i++) adj.add(new ArrayList<>());
            for (Edge e : m.edges) { adj.get(e.a).add(e.b); adj.get(e.b).add(e.a); }
            for (Edge e : m.transfers) { adj.get(e.a).add(e.b); adj.get(e.b).add(e.a); }
            boolean[] vis = new boolean[m.stations.size()];
            Deque<Integer> q = new ArrayDeque<>(List.of(0));
            vis[0] = true;
            while (!q.isEmpty()) for (int n : adj.get(q.poll())) if (!vis[n]) { vis[n] = true; q.add(n); }
            List<String> lost = new ArrayList<>();
            for (int i = 0; i < vis.length; i++) if (!vis[i]) lost.add(m.stations.get(i).name + " (" + m.stations.get(i).line + ")");
            if (!lost.isEmpty()) errors.add("До этих станций нельзя доехать ни по линиям, ни через переходы: " + String.join(", ", lost.subList(0, Math.min(lost.size(), 20))) + (lost.size() > 20 ? " …" : ""));
        }

        layout(m);
        return m;
    }

    static void report(Model m) {
        System.out.println("Линий: " + m.lines.size() + ", станций: " + m.stations.size() + ", перегонов: " + m.edges.size() + ", переходов: " + m.transfers.size());
        for (String w : warnings) System.out.println("  ! " + w);
        for (String e : errors) System.out.println("  ОШИБКА: " + e);
        System.out.println(errors.isEmpty() ? "Данные в порядке." : "Найдено ошибок: " + errors.size() + ". Исправьте их и запустите снова.");
    }

    // Время в пути между соседними станциями, если не задано в minutes
    static double rideMinutes(Station a, Station b, String type) {
        double d = distKm(a, b); // по прямой; коэффициенты подобраны по реальному времени в пути
        double t;
        switch (type) {
            case "mcd": t = 1.0 + d * 1.3; break;
            case "mcc": t = 0.9 + d * 1.15; break;
            default: t = 0.9 + d * 0.85;
        }
        return Math.round(Math.max(1.0, t) * 10) / 10.0;
    }

    static double distKm(Station a, Station b) {
        double R = 6371, dLat = Math.toRadians(b.lat - a.lat), dLon = Math.toRadians(b.lon - a.lon);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }

    // ------------------------------------------------------------------ layout

    /** Центр схемы и «линза»: центр города растягивается, окраины сжимаются, чтобы схема читалась на телефоне. */
    static final double C_LAT = 55.7525, C_LON = 37.6215, POWER = 0.62, SCALE = 260;

    static void layout(Model m) {
        for (Station s : m.stations) {
            if (s.manualXY || Double.isNaN(s.lat)) continue;
            double kx = (s.lon - C_LON) * 111.32 * Math.cos(Math.toRadians(C_LAT));
            double ky = -(s.lat - C_LAT) * 111.13;
            double r = Math.hypot(kx, ky);
            double k = r < 1e-6 ? 0 : Math.pow(r, POWER) / r;
            s.x = Math.round(kx * k * SCALE * 10) / 10.0;
            s.y = Math.round(ky * k * SCALE * 10) / 10.0;
        }
    }

    // ------------------------------------------------------------------ output

    static void writeAssets(Model m, Version v) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n\"updated\":").append(js(m.info.getProperty("updated", LocalDate.now().toString())));
        sb.append(",\n\"source\":").append(js(m.info.getProperty("source", "")));
        sb.append(",\n\"version\":").append(js(v.name));
        sb.append(",\n\"lines\":[");
        boolean first = true;
        for (Line l : m.lines.values()) {
            sb.append(first ? "\n" : ",\n");
            first = false;
            sb.append("{\"id\":").append(js(l.id)).append(",\"name\":").append(js(l.name)).append(",\"color\":").append(js(l.color))
              .append(",\"type\":").append(js(l.type)).append(",\"ring\":").append(l.ring).append(",\"wait\":").append(num(l.wait)).append(",\"emoji\":").append(js(l.emoji)).append(",\"paths\":[");
            sb.append(m.paths.get(l.id).stream().map(p -> p.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"))).collect(Collectors.joining(",")));
            sb.append("]}");
        }
        sb.append("\n],\n\"stations\":[");
        for (int i = 0; i < m.stations.size(); i++) {
            Station s = m.stations.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("{\"l\":").append(js(s.line)).append(",\"n\":").append(js(s.name)).append(",\"x\":").append(num(s.x)).append(",\"y\":").append(num(s.y))
              .append(",\"lat\":").append(num(s.lat)).append(",\"lon\":").append(num(s.lon)).append("}");
        }
        sb.append("\n],\n\"edges\":[");
        sb.append(m.edges.stream().map(e -> "[" + e.a + "," + e.b + "," + num(e.min) + "]").collect(Collectors.joining(",")));
        sb.append("],\n\"transfers\":[");
        sb.append(m.transfers.stream().map(e -> "[" + e.a + "," + e.b + "," + num(e.min) + "]").collect(Collectors.joining(",")));
        sb.append("]\n}\n");
        // одни и те же данные для Android-приложения и для Telegram (Mini App в docs/, бот читает оттуда же)
        for (String target : new String[]{"app/src/main/assets/metro.json", "docs/metro.json"}) {
            Path out = ROOT.resolve(target);
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("Данные записаны: " + target);
        }
    }

    static Path writePreview(Model m) throws IOException {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Station s : m.stations) { minX = Math.min(minX, s.x); minY = Math.min(minY, s.y); maxX = Math.max(maxX, s.x); maxY = Math.max(maxY, s.y); }
        double pad = 120;
        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT, "<svg id='map' xmlns='http://www.w3.org/2000/svg' viewBox='%.0f %.0f %.0f %.0f'>", minX - pad, minY - pad, maxX - minX + 2 * pad, maxY - minY + 2 * pad));
        for (Edge t : m.transfers) {
            Station a = m.stations.get(t.a), b = m.stations.get(t.b);
            svg.append(String.format(Locale.ROOT, "<line x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f' stroke='#999' stroke-width='9' stroke-linecap='round'/>", a.x, a.y, b.x, b.y));
        }
        for (Line l : m.lines.values()) for (List<Integer> p : m.paths.get(l.id)) {
            String pts = p.stream().map(i -> String.format(Locale.ROOT, "%.1f,%.1f", m.stations.get(i).x, m.stations.get(i).y)).collect(Collectors.joining(" "));
            svg.append("<polyline fill='none' stroke='").append(l.color).append("' stroke-width='").append(l.type.equals("metro") ? 7 : 6)
               .append("' stroke-linejoin='round' points='").append(pts).append("'/>");
            if (!l.type.equals("metro")) svg.append("<polyline fill='none' stroke='#fff' stroke-width='2.5' points='").append(pts).append("'/>");
        }
        for (Station s : m.stations) {
            Line l = m.lines.get(s.line);
            svg.append(String.format(Locale.ROOT, "<circle cx='%.1f' cy='%.1f' r='5.5' fill='%s' stroke='#fff' stroke-width='2'><title>%s — %s</title></circle>", s.x, s.y, l.color, esc(s.name), esc(l.name)));
            svg.append(String.format(Locale.ROOT, "<text x='%.1f' y='%.1f' font-size='11'>%s</text>", s.x + 8, s.y + 4, esc(s.name)));
        }
        svg.append("</svg>");
        String html = "<!doctype html><html lang='ru'><meta charset='utf-8'><title>Схема — предпросмотр</title>"
            + "<style>html,body{margin:0;height:100%;font-family:system-ui,sans-serif;background:#fafafa}#map{width:100%;height:100%;cursor:grab}text{fill:#222;paint-order:stroke;stroke:#fafafa;stroke-width:3px}"
            + "#hint{position:fixed;left:12px;top:12px;background:#fff;padding:6px 10px;border-radius:6px;box-shadow:0 1px 4px #0003;font-size:13px}</style>"
            + "<div id='hint'>Колесо — масштаб, перетаскивание — сдвиг. Наведите на станцию, чтобы увидеть название и линию. "
            + "Станций: " + m.stations.size() + "</div>" + svg
            + "<script>const s=document.getElementById('map');let vb=s.viewBox.baseVal;let d=null;"
            + "s.onwheel=e=>{e.preventDefault();const k=e.deltaY>0?1.15:1/1.15;const r=s.getBoundingClientRect();const px=vb.x+(e.clientX-r.left)/r.width*vb.width,py=vb.y+(e.clientY-r.top)/r.height*vb.height;"
            + "vb.x=px-(px-vb.x)*k;vb.y=py-(py-vb.y)*k;vb.width*=k;vb.height*=k;};"
            + "s.onmousedown=e=>d=[e.clientX,e.clientY];onmouseup=()=>d=null;onmousemove=e=>{if(!d)return;const r=s.getBoundingClientRect();const k=Math.max(vb.width/r.width,vb.height/r.height);"
            + "vb.x-=(e.clientX-d[0])*k;vb.y-=(e.clientY-d[1])*k;d=[e.clientX,e.clientY];};</script></html>";
        Path out = ROOT.resolve("dist/preview.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        return out;
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace("'", "&#39;"); }

    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static String num(double d) {
        if (Double.isNaN(d)) return "null";
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    // ------------------------------------------------------------------ APK

    static Path buildApk(Version v, boolean release) throws Exception {
        Path sdk = findSdk();
        Path bt = latestDir(sdk.resolve("build-tools"), p -> Files.exists(p.resolve("lib/d8.jar")));
        Path platform = latestDir(sdk.resolve("platforms"), p -> Files.exists(p.resolve("android.jar")));
        if (bt == null) fail("В Android SDK не найдены build-tools. Установите их в Android Studio: Settings → Android SDK → SDK Tools → Android SDK Build-Tools.");
        if (platform == null) fail("В Android SDK не найдена платформа. Установите в Android Studio: Settings → Android SDK → SDK Platforms → Android 14 (API 34) или новее.");
        Path androidJar = platform.resolve("android.jar");
        boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path aapt2 = bt.resolve(win ? "aapt2.exe" : "aapt2");
        System.out.println("Android SDK: " + sdk + "  (build-tools " + bt.getFileName() + ", " + platform.getFileName() + ")");

        Path app = ROOT.resolve("app/src/main");
        Path build = ROOT.resolve("build");
        deleteTree(build);
        Files.createDirectories(build);

        Properties ap = new Properties();
        try (Reader r = Files.newBufferedReader(ROOT.resolve("app.properties"), StandardCharsets.UTF_8)) { ap.load(r); }
        String minSdk = ap.getProperty("minSdk", "26"), targetSdk = ap.getProperty("targetSdk", "35");

        String appId = ap.getProperty("applicationId", "ru.moscowmetro.offline").trim();
        String manifest = Files.readString(app.resolve("AndroidManifest.xml"), StandardCharsets.UTF_8)
            .replaceFirst("<manifest\\s", "<manifest package=\"" + appId + "\" ");
        Files.writeString(build.resolve("AndroidManifest.xml"), manifest, StandardCharsets.UTF_8);

        System.out.println("1/5 Ресурсы…");
        run(aapt2.toString(), "compile", "--dir", app.resolve("res").toString(), "-o", build.resolve("res.zip").toString());
        Files.createDirectories(build.resolve("gen"));
        run(aapt2.toString(), "link", "-I", androidJar.toString(), "--manifest", build.resolve("AndroidManifest.xml").toString(),
            "-A", app.resolve("assets").toString(), "--java", build.resolve("gen").toString(),
            "--min-sdk-version", minSdk, "--target-sdk-version", targetSdk,
            "--version-code", String.valueOf(v.code), "--version-name", v.name,
            "-o", build.resolve("base.apk").toString(), build.resolve("res.zip").toString());

        System.out.println("2/5 Компиляция Java…");
        List<String> srcs;
        try (Stream<Path> s = Stream.concat(Files.walk(app.resolve("java")), Files.walk(build.resolve("gen")))) {
            srcs = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).collect(Collectors.toList());
        }
        Path classes = build.resolve("classes");
        Files.createDirectories(classes);
        List<String> javac = new ArrayList<>(List.of(tool("javac"), "-encoding", "UTF-8", "--release", "11", "-nowarn", "-Xlint:none",
            "-classpath", androidJar.toString(), "-d", classes.toString()));
        javac.addAll(srcs);
        run(javac.toArray(new String[0]));

        System.out.println("3/5 DEX…");
        List<String> classFiles;
        try (Stream<Path> s = Files.walk(classes)) {
            classFiles = s.filter(p -> p.toString().endsWith(".class")).map(Path::toString).collect(Collectors.toList());
        }
        Files.createDirectories(build.resolve("dex"));
        List<String> d8 = new ArrayList<>(List.of(tool("java"), "-cp", bt.resolve("lib/d8.jar").toString(), "com.android.tools.r8.D8",
            "--release", "--min-api", minSdk, "--lib", androidJar.toString(), "--output", build.resolve("dex").toString()));
        d8.addAll(classFiles);
        run(d8.toArray(new String[0]));

        System.out.println("4/5 Упаковка…");
        Path unsigned = build.resolve("unsigned.apk");
        packageApk(build.resolve("base.apk"), build.resolve("dex/classes.dex"), unsigned);

        System.out.println("5/5 Подпись…");
        Path ksProps = ROOT.resolve("keystore/keystore.properties");
        if (!Files.exists(ksProps)) createKeystore(ksProps);
        Properties kp = new Properties();
        try (Reader r = Files.newBufferedReader(ksProps, StandardCharsets.UTF_8)) { kp.load(r); }
        Path ks = ROOT.resolve("keystore").resolve(kp.getProperty("storeFile"));
        Path out = ROOT.resolve("dist/metro-moscow-" + v.name + (release ? "" : "-test") + ".apk");
        Files.createDirectories(out.getParent());
        run(tool("java"), "-jar", bt.resolve("lib/apksigner.jar").toString(), "sign",
            "--ks", ks.toString(), "--ks-key-alias", kp.getProperty("keyAlias"),
            "--ks-pass", "pass:" + kp.getProperty("storePassword"), "--key-pass", "pass:" + kp.getProperty("keyPassword"),
            "--min-sdk-version", minSdk, "--out", out.toString(), unsigned.toString());
        Files.deleteIfExists(Paths.get(out + ".idsig"));
        return out;
    }

    /** Копирует ресурсы из base.apk, добавляет classes.dex и выравнивает несжатые файлы по 4 байта (как zipalign). */
    static void packageApk(Path base, Path dex, Path out) throws IOException {
        List<Object[]> entries = new ArrayList<>();
        try (ZipFile z = new ZipFile(base.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                try (InputStream in = z.getInputStream(e)) {
                    String n = e.getName();
                    boolean store = e.getMethod() == ZipEntry.STORED || n.equals("resources.arsc") || n.endsWith(".png");
                    entries.add(new Object[]{n, in.readAllBytes(), store});
                }
            }
        }
        entries.add(new Object[]{"classes.dex", Files.readAllBytes(dex), false});
        try (CountingOutputStream cos = new CountingOutputStream(Files.newOutputStream(out)); ZipOutputStream zos = new ZipOutputStream(cos)) {
            for (Object[] e : entries) {
                String name = (String) e[0];
                byte[] data = (byte[]) e[1];
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(1577836800000L);
                if ((Boolean) e[2]) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(data.length);
                    ze.setCompressedSize(data.length);
                    ze.setCrc(crc.getValue());
                    zos.flush();
                    long headerStart = cos.count;
                    int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
                    long dataStart = headerStart + 30 + nameLen;
                    int padding = (int) ((4 - (dataStart + 4) % 4) % 4);
                    byte[] extra = new byte[4 + padding];
                    extra[0] = (byte) 0xD9; extra[1] = (byte) 0x35; // зарезервированный id поля выравнивания
                    extra[2] = (byte) padding;
                    ze.setExtra(extra);
                } else {
                    ze.setMethod(ZipEntry.DEFLATED);
                }
                zos.putNextEntry(ze);
                zos.write(data);
                zos.closeEntry();
            }
        }
    }

    static class CountingOutputStream extends FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }

    static void createKeystore(Path props) throws Exception {
        System.out.println("Ключа подписи ещё нет — создаю новый в папке keystore/.");
        System.out.println("ВАЖНО: сохраните папку keystore/ в надёжном месте. Без неё нельзя выпустить обновление, которое встанет поверх установленного приложения.");
        Files.createDirectories(props.getParent());
        String pass = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Path ks = props.getParent().resolve("metro-release.jks");
        run(tool("keytool"), "-genkeypair", "-keystore", ks.toString(), "-storetype", "PKCS12", "-alias", "metro",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-storepass", pass, "-keypass", pass,
            "-dname", "CN=Metro Moscow, O=Metro Moscow, C=RU");
        Files.writeString(props, "storeFile=metro-release.jks\nkeyAlias=metro\nstorePassword=" + pass + "\nkeyPassword=" + pass + "\n", StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    static class Version { int code; String name; }

    static Version readVersion() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(ROOT.resolve("version.properties"), StandardCharsets.UTF_8)) { p.load(r); }
        Version v = new Version();
        v.code = Integer.parseInt(p.getProperty("versionCode", "1").trim());
        v.name = p.getProperty("versionName", "1.0").trim();
        return v;
    }

    static void writeVersion(Version v) throws IOException {
        Files.writeString(ROOT.resolve("version.properties"),
            "# Меняется автоматически командой release\nversionCode=" + v.code + "\nversionName=" + v.name + "\n", StandardCharsets.UTF_8);
    }

    static List<Map<String, String>> readCsv(Path p) throws IOException {
        if (!Files.exists(p)) { errors.add("Нет файла " + ROOT.relativize(p)); return List.of(); }
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        String[] lines = text.split("\r?\n");
        if (lines.length == 0) return List.of();
        char delim = lines[0].contains(";") ? ';' : (lines[0].contains("\t") ? '\t' : ',');
        List<String> header = splitCsv(lines[0], delim).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toList());
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty() || lines[i].startsWith("#")) continue;
            List<String> cells = splitCsv(lines[i], delim);
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < header.size(); c++) row.put(header.get(c), c < cells.size() ? cells.get(c).trim() : "");
            row.put("_row", String.valueOf(i + 1));
            rows.add(row);
        }
        return rows;
    }

    static List<String> splitCsv(String line, char d) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == d) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    static String req(Map<String, String> r, String k) {
        String v = r.get(k);
        if (v == null) { errors.add("Нет столбца «" + k + "»"); return ""; }
        return v.trim();
    }

    static boolean isTrue(String s) { return s != null && Set.of("1", "да", "yes", "true").contains(s.trim().toLowerCase(Locale.ROOT)); }

    static double parseD(String s, double def, String where) {
        if (s == null || s.trim().isEmpty()) return def;
        try { return Double.parseDouble(s.trim().replace(',', '.')); }
        catch (NumberFormatException e) { errors.add(where + ": «" + s + "» — не число"); return def; }
    }

    static Path findRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (Path c = p; c != null; c = c.getParent()) if (Files.exists(c.resolve("data/stations.csv"))) return c;
        return p;
    }

    static Path findSdk() throws IOException {
        List<String> cands = new ArrayList<>();
        for (String env : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) if (System.getenv(env) != null) cands.add(System.getenv(env));
        Path lp = ROOT.resolve("local.properties");
        if (Files.exists(lp)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(lp)) { p.load(r); }
            if (p.getProperty("sdk.dir") != null) cands.add(0, p.getProperty("sdk.dir"));
        }
        String home = System.getProperty("user.home");
        if (System.getenv("LOCALAPPDATA") != null) cands.add(System.getenv("LOCALAPPDATA") + "/Android/Sdk");
        cands.add(home + "/Android/Sdk");
        cands.add(home + "/Library/Android/sdk");
        for (String c : cands) if (Files.isDirectory(Paths.get(c, "build-tools"))) return Paths.get(c);
        fail("Не найден Android SDK. Установите Android Studio (она поставит SDK) или укажите путь в файле local.properties: sdk.dir=C\\:\\\\Users\\\\Имя\\\\AppData\\\\Local\\\\Android\\\\Sdk");
        return null;
    }

    static Path latestDir(Path dir, java.util.function.Predicate<Path> ok) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).filter(ok).filter(p -> p.getFileName().toString().matches("(android-)?[0-9]+(\\.[0-9]+)*"))
                .max(Comparator.comparing(p -> verKey(p.getFileName().toString()))).orElse(null);
        }
    }

    static String verKey(String n) {
        return Arrays.stream(n.replaceAll("[^0-9.]", "").split("\\.")).map(x -> String.format("%05d", x.isEmpty() ? 0 : Integer.parseInt(x))).collect(Collectors.joining("."));
    }

    static String tool(String name) {
        boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path p = Paths.get(System.getProperty("java.home"), "bin", name + (win ? ".exe" : ""));
        return Files.exists(p) ? p.toString() : name;
    }

    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            System.out.println(out);
            fail("Команда завершилась с ошибкой (" + code + "): " + cmd[0]);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(x);
        }
    }

    static void openInBrowser(Path p) {
        try { java.awt.Desktop.getDesktop().browse(p.toUri()); } catch (Throwable ignored) { }
    }

    static void fail(String msg) {
        System.out.println("ОШИБКА: " + msg);
        System.exit(1);
    }
}
