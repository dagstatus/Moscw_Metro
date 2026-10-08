package ru.moscowmetro.offline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Поиск самого быстрого маршрута (алгоритм Дейкстры) по графу станций.
 * Перегон стоит столько минут, сколько указано в данных; переход — время пешком плюс среднее ожидание поезда.
 */
final class Router {

    static final class Step {
        boolean walk;
        int line = -1;
        final List<Integer> stations = new ArrayList<>();
        float minutes;
    }

    static final class Route {
        final List<Step> steps = new ArrayList<>();
        final List<Integer> nodes = new ArrayList<>();
        float minutes;
        int transfers, stops;
    }

    private final MetroData d;
    private final int[][] adj;
    private final float[][] cost;
    private final boolean[][] isWalk;

    Router(MetroData d) {
        this.d = d;
        int n = d.stations.length;
        int[] deg = new int[n];
        for (int i = 0; i < d.edgeA.length; i++) { deg[d.edgeA[i]]++; deg[d.edgeB[i]]++; }
        for (int i = 0; i < d.trA.length; i++) { deg[d.trA[i]]++; deg[d.trB[i]]++; }
        adj = new int[n][];
        cost = new float[n][];
        isWalk = new boolean[n][];
        for (int i = 0; i < n; i++) { adj[i] = new int[deg[i]]; cost[i] = new float[deg[i]]; isWalk[i] = new boolean[deg[i]]; }
        int[] fill = new int[n];
        for (int i = 0; i < d.edgeA.length; i++) {
            add(fill, d.edgeA[i], d.edgeB[i], d.edgeMin[i], false);
            add(fill, d.edgeB[i], d.edgeA[i], d.edgeMin[i], false);
        }
        for (int i = 0; i < d.trA.length; i++) {
            int a = d.trA[i], b = d.trB[i];
            add(fill, a, b, d.trMin[i] + d.lines[d.stations[b].line].wait, true);
            add(fill, b, a, d.trMin[i] + d.lines[d.stations[a].line].wait, true);
        }
    }

    private void add(int[] fill, int a, int b, float c, boolean walk) {
        int k = fill[a]++;
        adj[a][k] = b; cost[a][k] = c; isWalk[a][k] = walk;
    }

    Route find(int[] from, int[] to) {
        int n = d.stations.length;
        float[] dist = new float[n];
        int[] prev = new int[n];
        boolean[] prevWalk = new boolean[n];
        boolean[] target = new boolean[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Float.MAX_VALUE);
        Arrays.fill(prev, -1);
        for (int t : to) target[t] = true;
        PriorityQueue<float[]> pq = new PriorityQueue<>((x, y) -> Float.compare(x[0], y[0]));
        for (int s : from) {
            if (target[s]) return null; // та же станция
            dist[s] = 0;
            pq.add(new float[]{0, s});
        }
        int end = -1;
        while (!pq.isEmpty()) {
            float[] cur = pq.poll();
            int u = (int) cur[1];
            if (done[u]) continue;
            done[u] = true;
            if (target[u]) { end = u; break; }
            for (int k = 0; k < adj[u].length; k++) {
                int v = adj[u][k];
                float nd = dist[u] + cost[u][k];
                if (nd < dist[v] - 1e-4f) {
                    dist[v] = nd; prev[v] = u; prevWalk[v] = isWalk[u][k];
                    pq.add(new float[]{nd, v});
                }
            }
        }
        if (end < 0) return null;

        List<Integer> nodes = new ArrayList<>();
        List<Boolean> walks = new ArrayList<>();
        for (int v = end; v >= 0; v = prev[v]) { nodes.add(0, v); walks.add(0, prevWalk[v]); }

        Route r = new Route();
        r.nodes.addAll(nodes);
        Step cur = null;
        for (int i = 0; i < nodes.size(); i++) {
            int v = nodes.get(i);
            if (i == 0) {
                cur = newRide(v);
                continue;
            }
            int u = nodes.get(i - 1);
            float c = dist[v] - dist[u];
            if (walks.get(i)) {
                if (cur.walk) {
                    cur.stations.add(v);
                    cur.minutes += c;
                } else {
                    if (cur.stations.size() > 1) r.steps.add(cur);
                    cur = new Step();
                    cur.walk = true;
                    cur.stations.add(u);
                    cur.stations.add(v);
                    cur.minutes = c;
                }
            } else {
                if (cur.walk) {
                    r.steps.add(cur);
                    cur = newRide(u);
                }
                cur.stations.add(v);
                cur.minutes += c;
            }
        }
        if (cur.walk || cur.stations.size() > 1) r.steps.add(cur);

        boolean firstRide = true;
        for (Step s : r.steps) {
            if (s.walk) {
                r.transfers++;
                // в переходе учтено ожидание следующего поезда; показываем только путь пешком
                Integer last = s.stations.get(s.stations.size() - 1);
                s.minutes = Math.max(1, s.minutes - d.lines[d.stations[last].line].wait);
            } else {
                r.stops += s.stations.size() - 1;
                if (firstRide) { r.minutes += d.lines[s.line].wait; firstRide = false; }
            }
        }
        r.minutes += dist[end];
        return r;
    }

    private Step newRide(int v) {
        Step s = new Step();
        s.line = d.stations[v].line;
        s.stations.add(v);
        return s;
    }

    /** Конечная станция в направлении движения (для линий без кольца). */
    String direction(Step s) {
        MetroData.Line l = d.lines[s.line];
        if (l.ring || s.stations.size() < 2) return null;
        int a = s.stations.get(s.stations.size() - 2), b = s.stations.get(s.stations.size() - 1);
        for (int[] p : l.paths) {
            for (int k = 0; k + 1 < p.length; k++) {
                if (p[k] == a && p[k + 1] == b) return d.stations[p[p.length - 1]].name;
                if (p[k] == b && p[k + 1] == a) return d.stations[p[0]].name;
            }
        }
        return null;
    }
}
