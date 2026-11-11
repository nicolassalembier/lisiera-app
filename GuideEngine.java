package fr.lisiera.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Moteur de guidage vers le poste (port exact de V1.58 et V1.62 de app.html).
 * Aucune dependance Android. Coordonnees : double[]{lat, lng}.
 */
public final class GuideEngine {
    private GuideEngine() { }

    static final double JOIN = 40, POST_TOL = 60, OFF = 30, OFF_HOLD = 10, ON = 15, KEEP = 20, ARR = 20,
            TURN_MIN = 30, TURN_FAR = 80, TURN_NEAR = 35, UT_DEG = 130, UT_HOLD = 5,
            V_DEF = 0.97, V_MIN = 0.42, V_MAX = 1.67, SPD_WIN = 60, OFF_EVERY = 60, UT_EVERY = 30,
            ACC_POOR = 35, LOOK = 25;

    public static final class Chem {
        public String key = "", parent = "", end = "";
        public double[][] geom = new double[0][];
        public int[] posts = new int[0];
        public double[][] closed = new double[0][];
    }

    static final class Edge { final int to; final double w; Edge(int to, double w) { this.to = to; this.w = w; } }
    static final class Line { String key; int[] seq; int[] posts; boolean[] closed; }
    static final class Ins {
        final int i; final double t; final int j; final double al;
        Ins(int i, double t, int j, double al) { this.i = i; this.t = t; this.j = j; this.al = al; }
    }
    static final class Snap { double d; int a, b; double[] pt; String line; }
    static final class Proj { double d, t, along, total; int i; double[] pt; }

    public static final class Graph {
        final ArrayList<double[]> pts = new ArrayList<double[]>();
        final ArrayList<Boolean> fork = new ArrayList<Boolean>();
        final ArrayList<ArrayList<Edge>> adj = new ArrayList<ArrayList<Edge>>();
        final ArrayList<ArrayList<Edge>> adjAll = new ArrayList<ArrayList<Edge>>();
        final ArrayList<Line> lines = new ArrayList<Line>();
        int nClosed;
        double cloLen;
        String cloSig = "";
        int add(double[] p, boolean f) {
            pts.add(new double[]{p[0], p[1]}); fork.add(f);
            adj.add(new ArrayList<Edge>()); adjAll.add(new ArrayList<Edge>());
            return pts.size() - 1;
        }
        void link(int a, int b, double w, boolean closed) {
            adjAll.get(a).add(new Edge(b, w)); adjAll.get(b).add(new Edge(a, w));
            if (!closed) { adj.get(a).add(new Edge(b, w)); adj.get(b).add(new Edge(a, w)); }
        }
        public boolean isEmpty() { return lines.isEmpty(); }
    }

    public static final class Turn { double s, delta; double[] pt; String dir; boolean fork; }
    public static final class Route { double[][] pts; double[] cum; double len; List<Turn> turns; }

    public static final class Ev {
        public String type, say, beep;
        public boolean crit;
        public long[] vib;
        Ev(String type, boolean crit, String say, long[] vib, String beep) { this.type = type; this.crit = crit; this.say = say; this.vib = vib; this.beep = beep; }
    }

    public static final class Out {
        public String mode = "route";
        public final List<Ev> events = new ArrayList<Ev>();
        public boolean poor, blocked;
        public double acc, rem = Double.NaN, eta = Double.NaN, v = V_DEF, bearing = Double.NaN, headingDeg = Double.NaN, offDist, dPost;
        public boolean hasNext, nextFork;
        public double nextDist, nextDelta;
        public String nextDir = "";
        public Route route;
        public double along;
    }

    public static final class State {
        Route route; Graph goalsFor; List<Snap> goals = new ArrayList<Snap>();
        long offSince, offAlert, utSince, utAlert, lastTs;
        boolean offAlerted, arrived, blk, hasP;
        String cloSig;
        double cloLen, pRem;
        int pN;
        final Map<String, Integer> ann = new HashMap<String, Integer>();
        final ArrayList<double[]> hist = new ArrayList<double[]>();   // {lat, lng, t}
        final ArrayList<double[]> samples = new ArrayList<double[]>(); // {t, rem}
        public void resetRoute() { route = null; goalsFor = null; }
        public void resetAll() {
            route = null; goalsFor = null; goals = new ArrayList<Snap>(); offSince = 0; offAlert = 0; offAlerted = false;
            utSince = 0; utAlert = 0; ann.clear(); hist.clear(); samples.clear(); arrived = false; lastTs = 0;
            blk = false; cloSig = null; cloLen = 0; hasP = false;
        }
    }

    /* ---------- geometrie ---------- */
    static double kx(double lat) { return 111320.0 * Math.cos(lat * Math.PI / 180.0); }
    static double dist(double[] a, double[] b) { double k = kx((a[0] + b[0]) / 2); return Math.hypot((b[1] - a[1]) * k, (b[0] - a[0]) * 110540.0); }
    static double brg(double[] a, double[] b) {
        double rad = Math.PI / 180.0, y = Math.sin((b[1] - a[1]) * rad) * Math.cos(b[0] * rad);
        double x = Math.cos(a[0] * rad) * Math.sin(b[0] * rad) - Math.sin(a[0] * rad) * Math.cos(b[0] * rad) * Math.cos((b[1] - a[1]) * rad);
        return (Math.atan2(y, x) / rad + 360.0) % 360.0;
    }
    static double nd(double x) { return (((x % 360.0) + 540.0) % 360.0) - 180.0; }
    static Proj proj(double[][] poly, double[] p) {
        if (poly == null || poly.length < 2) return null;
        Proj best = null;
        double acc = 0;
        for (int i = 0; i < poly.length - 1; i++) {
            double[] a = poly[i], b = poly[i + 1];
            double k = kx(a[0]);
            double bx = (b[1] - a[1]) * k, by = (b[0] - a[0]) * 110540.0, px = (p[1] - a[1]) * k, py = (p[0] - a[0]) * 110540.0;
            double l2 = bx * bx + by * by, t = l2 > 0 ? Math.max(0, Math.min(1, (px * bx + py * by) / l2)) : 0;
            double cx = bx * t, cy = by * t, d = Math.hypot(px - cx, py - cy), sg = Math.sqrt(l2);
            if (best == null || d < best.d) {
                best = new Proj(); best.d = d; best.i = i; best.t = t;
                best.pt = new double[]{a[0] + cy / 110540.0, a[1] + cx / k};
                best.along = acc + sg * t;
            }
            acc += sg;
        }
        best.total = acc;
        return best;
    }

    /* ---------- reseau : embranchements, fins raccordees, portions fermees ---------- */
    private static void put(Map<String, ArrayList<Ins>> ins, String key, int i, double t, int j) {
        ArrayList<Ins> l = ins.get(key);
        if (l == null) { l = new ArrayList<Ins>(); ins.put(key, l); }
        l.add(new Ins(i, t, j, 0));
    }

    public static Graph build(List<Chem> chems) {
        Graph G = new Graph();
        Map<String, Chem> by = new HashMap<String, Chem>();
        for (Chem c : chems) if (!by.containsKey(c.key)) by.put(c.key, c);
        Map<String, int[]> vid = new HashMap<String, int[]>();
        for (Chem c : chems) {
            int[] ids = new int[c.geom.length];
            for (int i = 0; i < ids.length; i++) ids[i] = G.add(c.geom[i], false);
            vid.put(c.key, ids);
        }
        Map<String, ArrayList<Ins>> ins = new HashMap<String, ArrayList<Ins>>();
        ArrayList<int[]> conn = new ArrayList<int[]>();
        for (Chem c : chems) {
            Chem p = c.parent.length() > 0 ? by.get(c.parent) : null;
            if (p != null && p != c) {
                Proj pr = proj(p.geom, c.geom[0]);
                if (pr != null && pr.d <= JOIN) { int j = G.add(pr.pt, true); put(ins, p.key, pr.i, pr.t, j); conn.add(new int[]{j, vid.get(c.key)[0]}); }
            }
            Chem e = c.end.length() > 0 ? by.get(c.end) : null;
            if (e != null && e == c && c.geom.length >= 3) {
                int[] ids0 = vid.get(c.key);
                int last0 = c.geom.length - 1;
                if (dist(c.geom[last0], c.geom[0]) <= JOIN) conn.add(new int[]{ids0[last0], ids0[0]});
            }
            if (e != null && e != c) {
                int last = c.geom.length - 1;
                Proj pr = proj(e.geom, c.geom[last]);
                if (pr != null && pr.d <= JOIN) { int j = G.add(pr.pt, true); put(ins, e.key, pr.i, pr.t, j); conn.add(new int[]{j, vid.get(c.key)[last]}); }
            }
        }
        ArrayList<String> sig = new ArrayList<String>();
        double cloLen = 0;
        for (Chem c : chems) {
            int[] ids = vid.get(c.key);
            double[] cv = new double[c.geom.length];
            for (int k = 1; k < cv.length; k++) cv[k] = cv[k - 1] + dist(c.geom[k - 1], c.geom[k]);
            double total = cv[cv.length - 1];
            ArrayList<double[]> cl = new ArrayList<double[]>();
            for (double[] r : c.closed) if (r[1] - r[0] >= 0.5 && r[0] < total) cl.add(new double[]{Math.max(0, r[0]), Math.min(total, r[1])});
            ArrayList<Ins> items = new ArrayList<Ins>();
            ArrayList<Ins> src = ins.get(c.key);
            if (src != null) for (Ins x : src) items.add(new Ins(x.i, x.t, x.j, cv[x.i] + x.t * (cv[x.i + 1] - cv[x.i])));
            for (double[] r : cl) {
                for (int z = 0; z < 2; z++) {
                    double s = r[z];
                    int i = 0;
                    while (i < cv.length - 2 && cv[i + 1] < s) i++;
                    double sg = cv[i + 1] - cv[i], t = sg > 0 ? Math.max(0, Math.min(1, (s - cv[i]) / sg)) : 0;
                    double[] a = c.geom[i], b = c.geom[i + 1];
                    int j = G.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t}, false);
                    items.add(new Ins(i, t, j, s));
                }
            }
            Collections.sort(items, new Comparator<Ins>() {
                @Override public int compare(Ins a, Ins b) { if (a.i != b.i) return a.i - b.i; return Double.compare(a.t, b.t); }
            });
            ArrayList<Integer> seq = new ArrayList<Integer>();
            ArrayList<Double> al = new ArrayList<Double>();
            for (int k = 0; k < ids.length; k++) {
                seq.add(ids[k]); al.add(cv[k]);
                if (k < ids.length - 1) for (Ins x : items) if (x.i == k) { seq.add(x.j); al.add(x.al); }
            }
            boolean[] ed = new boolean[Math.max(0, seq.size() - 1)];
            for (int k = 0; k < seq.size() - 1; k++) {
                double w = dist(G.pts.get(seq.get(k)), G.pts.get(seq.get(k + 1))), mid = (al.get(k) + al.get(k + 1)) / 2;
                boolean closed = false;
                if (w >= 0.05) for (double[] r : cl) if (mid >= r[0] - 0.01 && mid <= r[1] + 0.01) { closed = true; break; }
                G.link(seq.get(k), seq.get(k + 1), w, closed);
                ed[k] = closed;
            }
            Line L = new Line();
            L.key = c.key; L.posts = c.posts; L.closed = ed;
            L.seq = new int[seq.size()];
            for (int k = 0; k < L.seq.length; k++) L.seq[k] = seq.get(k);
            G.lines.add(L);
            for (double[] r : cl) { sig.add(c.key + ":" + Math.round(r[0]) + "-" + Math.round(r[1])); cloLen += r[1] - r[0]; }
        }
        for (int[] x : conn) G.link(x[0], x[1], dist(G.pts.get(x[0]), G.pts.get(x[1])), false);
        Collections.sort(sig);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sig.size(); i++) { if (i > 0) sb.append('|'); sb.append(sig.get(i)); }
        G.nClosed = sig.size(); G.cloSig = sb.toString(); G.cloLen = cloLen;
        return G;
    }

    static Snap snap(Graph G, double[] p, String onlyLine, boolean all) {
        Snap best = null;
        for (Line L : G.lines) {
            if (onlyLine != null && !L.key.equals(onlyLine)) continue;
            for (int k = 0; k < L.seq.length - 1; k++) {
                if (L.closed[k] && !all) continue;
                int a = L.seq[k], b = L.seq[k + 1];
                double[] A = G.pts.get(a), B = G.pts.get(b);
                if (dist(A, B) < 0.01) continue;
                Proj pr = proj(new double[][]{A, B}, p);
                if (best == null || pr.d < best.d) { best = new Snap(); best.d = pr.d; best.a = a; best.b = b; best.pt = pr.pt; best.line = L.key; }
            }
        }
        return best;
    }

    static List<Snap> goals(Graph G, int postN, double[] postPos, boolean all) {
        List<Snap> out = new ArrayList<Snap>();
        for (Line L : G.lines) {
            boolean serves = false;
            for (int n : L.posts) if (n == postN) serves = true;
            if (!serves) continue;
            Snap s = snap(G, postPos, L.key, all);
            if (s != null && s.d <= POST_TOL) out.add(s);
        }
        if (out.isEmpty()) { Snap s = snap(G, postPos, null, all); if (s != null && s.d <= POST_TOL) out.add(s); }
        return out;
    }

    static final class Path { double len; ArrayList<Integer> ids; int goal; }

    static Path path(Graph G, Snap s, List<Snap> goals, boolean all) {
        int n = G.pts.size(), V = n + 1 + goals.size();
        @SuppressWarnings("unchecked") ArrayList<Edge>[] extra = (ArrayList<Edge>[]) new ArrayList[V];
        addE(extra, n, s.a, dist(s.pt, G.pts.get(s.a)));
        addE(extra, n, s.b, dist(s.pt, G.pts.get(s.b)));
        for (int k = 0; k < goals.size(); k++) {
            Snap g = goals.get(k);
            int gi = n + 1 + k;
            addE(extra, gi, g.a, dist(g.pt, G.pts.get(g.a)));
            addE(extra, gi, g.b, dist(g.pt, G.pts.get(g.b)));
            if ((g.a == s.a && g.b == s.b) || (g.a == s.b && g.b == s.a)) addE(extra, n, gi, dist(s.pt, g.pt));
        }
        double[] dist = new double[V];
        int[] prev = new int[V];
        boolean[] done = new boolean[V];
        for (int i = 0; i < V; i++) { dist[i] = Double.POSITIVE_INFINITY; prev[i] = -1; }
        dist[n] = 0;
        int end = -1;
        for (;;) {
            int u = -1;
            double bd = Double.POSITIVE_INFINITY;
            for (int i = 0; i < V; i++) if (!done[i] && dist[i] < bd) { bd = dist[i]; u = i; }
            if (u < 0) break;
            done[u] = true;
            if (u > n) { end = u; break; }
            if (u < n) for (Edge e : (all ? G.adjAll.get(u) : G.adj.get(u))) { double w = bd + e.w; if (!done[e.to] && w < dist[e.to]) { dist[e.to] = w; prev[e.to] = u; } }
            if (extra[u] != null) for (Edge e : extra[u]) { double w = bd + e.w; if (!done[e.to] && w < dist[e.to]) { dist[e.to] = w; prev[e.to] = u; } }
        }
        if (end < 0) return null;
        ArrayList<Integer> ids = new ArrayList<Integer>();
        for (int u = end; u >= 0; u = prev[u]) ids.add(0, u);
        Path p = new Path(); p.len = dist[end]; p.ids = ids; p.goal = end - n - 1;
        return p;
    }

    private static void addE(ArrayList<Edge>[] extra, int u, int v, double w) {
        if (extra[u] == null) extra[u] = new ArrayList<Edge>();
        if (extra[v] == null) extra[v] = new ArrayList<Edge>();
        extra[u].add(new Edge(v, w));
        extra[v].add(new Edge(u, w));
    }

    /* ---------- itineraire ---------- */
    static double[] cum(double[][] pts) {
        double[] c = new double[pts.length];
        for (int i = 1; i < pts.length; i++) c[i] = c[i - 1] + dist(pts[i - 1], pts[i]);
        return c;
    }
    public static double[] at(Route r, double s) {
        s = Math.max(0, Math.min(r.len, s));
        double[] c = r.cum;
        int i = 1;
        while (i < c.length - 1 && c[i] < s) i++;
        double[] a = r.pts[i - 1], b = r.pts[i];
        double sg = c[i] - c[i - 1], t = sg > 0 ? (s - c[i - 1]) / sg : 0;
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }
    static String tDir(double d) {
        double a = Math.abs(d);
        return a >= 135 ? "uturn" : (a >= 60 ? (d > 0 ? "right" : "left") : (d > 0 ? "sright" : "sleft"));
    }
    static List<Turn> turns(Route r, LinkedHashSet<Integer> forks) {
        List<Turn> out = new ArrayList<Turn>();
        List<double[]> raw = new ArrayList<double[]>(); // {s, delta, big}
        List<double[]> rawPt = new ArrayList<double[]>();
        for (int k = 1; k < r.pts.length - 1; k++) {
            double s = r.cum[k];
            double[] a = at(r, s - 12), b = at(r, s + 12), v = r.pts[k];
            if (dist(a, v) < 0.5 || dist(v, b) < 0.5) continue;
            double d = nd(brg(v, b) - brg(a, v));
            if (!raw.isEmpty() && s - raw.get(raw.size() - 1)[0] < 10) {
                double[] last = raw.get(raw.size() - 1);
                if (Math.abs(d) > last[2]) { last[0] = s; rawPt.set(raw.size() - 1, v); last[2] = Math.abs(d); }
                last[1] = nd(last[1] + d);
                continue;
            }
            raw.add(new double[]{s, d, Math.abs(d)});
            rawPt.add(v);
        }
        for (int i = 0; i < raw.size(); i++) {
            double[] q = raw.get(i);
            if (Math.abs(q[1]) < TURN_MIN) continue;
            Turn t = new Turn(); t.s = q[0]; t.pt = rawPt.get(i); t.delta = q[1]; t.dir = tDir(q[1]); t.fork = false;
            out.add(t);
        }
        for (int k : forks) {
            if (k <= 0 || k >= r.pts.length - 1) continue;
            double s = r.cum[k];
            boolean near = false;
            for (Turn t : out) if (Math.abs(t.s - s) < 15) near = true;
            if (near) continue;
            double[] a = at(r, s - 12), b = at(r, s + 12), v = r.pts[k];
            if (dist(a, v) < 0.5 || dist(v, b) < 0.5) continue;
            Turn t = new Turn(); t.s = s; t.pt = v; t.delta = nd(brg(v, b) - brg(a, v)); t.dir = "straight"; t.fork = true;
            out.add(t);
        }
        Collections.sort(out, new Comparator<Turn>() { @Override public int compare(Turn x, Turn y) { return Double.compare(x.s, y.s); } });
        return out;
    }
    static Route route(Graph G, Snap sn, List<Snap> goals, double[] postPos) {
        Path best = path(G, sn, goals, false);
        if (best == null) return null;
        int n = G.pts.size();
        ArrayList<double[]> pts = new ArrayList<double[]>();
        LinkedHashSet<Integer> forks = new LinkedHashSet<Integer>();
        pushPt(pts, forks, sn.pt, false);
        for (int id : best.ids) if (id < n) pushPt(pts, forks, G.pts.get(id), G.fork.get(id));
        pushPt(pts, forks, goals.get(best.goal).pt, false);
        if (dist(pts.get(pts.size() - 1), postPos) > 0.5) pushPt(pts, forks, postPos, false);
        if (pts.size() < 2) pts.add(new double[]{pts.get(0)[0], pts.get(0)[1]});
        Route r = new Route();
        r.pts = pts.toArray(new double[0][]);
        r.cum = cum(r.pts);
        r.len = r.cum[r.cum.length - 1];
        r.turns = turns(r, forks);
        return r;
    }
    private static void pushPt(ArrayList<double[]> pts, LinkedHashSet<Integer> forks, double[] p, boolean f) {
        if (!pts.isEmpty() && dist(pts.get(pts.size() - 1), p) < 0.05) { if (f) forks.add(pts.size() - 1); return; }
        pts.add(new double[]{p[0], p[1]});
        if (f) forks.add(pts.size() - 1);
    }

    /* ---------- moteur ---------- */
    static String lbl(String d) {
        switch (d) {
            case "right": return "à droite"; case "left": return "à gauche"; case "sright": return "légèrement à droite";
            case "sleft": return "légèrement à gauche"; case "uturn": return "demi-tour"; default: return "tout droit";
        }
    }
    static String say(String d) {
        switch (d) {
            case "right": return "À droite"; case "left": return "À gauche"; case "sright": return "Légèrement à droite";
            case "sleft": return "Légèrement à gauche"; case "uturn": return "Faites demi-tour"; default: return "Tout droit à l’embranchement";
        }
    }
    static long[] vib(String d) {
        switch (d) {
            case "right": return new long[]{400}; case "left": return new long[]{150, 100, 150}; case "sright": return new long[]{250};
            case "sleft": return new long[]{100, 80, 100}; case "uturn": return new long[]{150, 100, 150, 100, 150}; default: return new long[]{120};
        }
    }

    /** cap de marche : cap GPS en marchant, sinon direction du deplacement recent ; NaN si inconnu */
    private static double moveHead(State st, double[] p, double speed, double heading) {
        if (!Double.isNaN(speed) && speed >= 0.8 && !Double.isNaN(heading) && !Double.isInfinite(heading)) return heading;
        for (int i = st.hist.size() - 1; i >= 0; i--) {
            double[] h = st.hist.get(i);
            if (dist(new double[]{h[0], h[1]}, p) >= 8) return brg(new double[]{h[0], h[1]}, p);
        }
        return Double.NaN;
    }

    /**
     * Un pas de guidage. acc = 0 si inconnue, speed/heading = NaN si inconnus, ts = heure de la mesure (ms), now = heure actuelle (ms).
     */
    public static Out step(State st, Graph G, int postN, double[] postPos, double lat, double lng, double acc0, long ts, double speed, double heading, long now) {
        String sigNow = G != null ? G.cloSig : "";
        Out out = stepCore(st, G, postN, postPos, lat, lng, acc0, ts, speed, heading, now);
        out.blocked = false;
        if (G != null && G.nClosed > 0 && out.mode.equals("direct")) {
            double[] p = new double[]{lat, lng};
            List<Snap> ga = goals(G, postN, postPos, true);
            Snap sa = snap(G, p, null, true);
            if (!ga.isEmpty() && sa != null && path(G, sa, ga, true) != null) out.blocked = true;
        }
        if (out.blocked) {
            if (!st.blk) { st.blk = true; out.events.add(new Ev("blocked", true, "Poste inaccessible : portion de chemin fermée. Direction à vol d’oiseau.", new long[]{300, 150, 300}, "2")); }
        } else if (!out.mode.equals("direct")) st.blk = false;
        if (G != null) {
            if (st.cloSig != null && !st.cloSig.equals(sigNow) && !out.blocked) {
                boolean ch = !st.hasP || out.route == null || Math.abs(st.pRem - out.rem) >= 3 || st.pN != out.route.pts.length;
                if (ch) {
                    st.ann.clear();
                    out.events.add(new Ev("reroute", true, G.cloLen > st.cloLen ? "Itinéraire modifié : portion de chemin fermée." : "Itinéraire recalculé : un passage a été rouvert.", new long[]{200, 100, 200, 100, 200}, null));
                }
            }
            st.cloSig = sigNow; st.cloLen = G.cloLen;
            if (out.route != null) { st.hasP = true; st.pRem = out.rem; st.pN = out.route.pts.length; } else st.hasP = false;
        }
        return out;
    }

    static Out stepCore(State st, Graph G, int postN, double[] postPos, double lat, double lng, double acc0, long ts, double speed, double heading, long now) {
        double[] p = new double[]{lat, lng};
        double acc = acc0 > 0 ? acc0 : 15;
        boolean poor = acc > ACC_POOR;
        Out out = new Out();
        out.poor = poor; out.acc = acc;
        double dPost = dist(p, postPos);
        out.dPost = dPost;
        if (ts != st.lastTs) {
            st.lastTs = ts;
            st.hist.add(new double[]{lat, lng, now});
            while (!st.hist.isEmpty() && now - st.hist.get(0)[2] > 20000) st.hist.remove(0);
        }
        double hd = moveHead(st, p, speed, heading);
        out.headingDeg = hd;
        if (!st.arrived && dPost <= ARR) {
            st.arrived = true;
            out.events.add(new Ev("arrive", true, "Vous êtes arrivé au poste " + postN, new long[]{400, 150, 150, 150, 400}, "arrive"));
        }
        if (st.arrived) { out.mode = "arrived"; out.bearing = brg(p, postPos); out.rem = dPost; return out; }
        if (G == null || G.lines.isEmpty()) {
            out.mode = "direct"; out.bearing = brg(p, postPos); out.rem = dPost; out.eta = now + dPost / V_DEF * 1000.0;
            return out;
        }
        if (st.goalsFor != G) { st.goalsFor = G; st.goals = goals(G, postN, postPos, false); st.route = null; }
        Snap sn = snap(G, p, null, false);
        Route nw = (sn != null && !st.goals.isEmpty()) ? route(G, sn, st.goals, postPos) : null;
        if (nw == null) {
            out.mode = "direct"; out.bearing = brg(p, postPos); out.rem = dPost; out.eta = now + dPost / V_DEF * 1000.0;
            return out;
        }
        Route route = null;
        double along = 0, dRoute = 0, on = Math.max(ON, Math.min(acc, 35));
        if (st.route != null) {
            Proj pr = proj(st.route.pts, p);
            if (pr != null && pr.d <= on && (st.route.len - pr.along) <= nw.len + KEEP) { route = st.route; along = pr.along; dRoute = pr.d; }
        }
        if (route == null) { route = nw; along = 0; dRoute = sn.d; st.route = nw; st.samples.clear(); }
        double rem = route.len - along + dRoute;
        out.rem = rem; out.route = route; out.along = along;
        out.bearing = brg(p, at(route, along + LOOK));
        st.samples.add(new double[]{now, rem});
        while (st.samples.size() > 1 && now - st.samples.get(0)[0] > SPD_WIN * 1000) st.samples.remove(0);
        double[] a0 = st.samples.get(0);
        double span = (now - a0[0]) / 1000.0, meas = span >= 20 ? (a0[1] - rem) / span : 0;
        out.v = meas >= 0.25 ? Math.max(V_MIN, Math.min(V_MAX, meas)) : V_DEF;
        out.eta = now + rem / out.v * 1000.0;
        boolean off = sn.d > Math.max(OFF, 1.5 * acc);
        if (off && !poor) {
            if (st.offSince == 0) st.offSince = now;
            out.mode = "off"; out.offDist = sn.d; out.bearing = brg(p, sn.pt);
            if (now - st.offSince >= OFF_HOLD * 1000 && now - st.offAlert >= OFF_EVERY * 1000) {
                st.offAlert = now; st.offAlerted = true;
                out.events.add(new Ev("off", true, "Hors chemin. Rejoignez le chemin à " + (long) Math.max(10, Math.round(sn.d / 10.0) * 10) + " mètres.", new long[]{500, 200, 500, 200, 500}, "2"));
            }
            return out;
        }
        if (st.offSince != 0) {
            if (st.offAlerted) { out.events.add(new Ev("back", false, "De retour sur le chemin.", new long[]{200}, null)); st.ann.clear(); }
            st.offSince = 0; st.offAlert = 0; st.offAlerted = false;
        }
        if (!poor && !Double.isNaN(hd)) {
            double hr = brg(at(route, along), at(route, Math.min(route.len, along + 15))), diff = Math.abs(nd(hd - hr));
            if (route.len - along > 5 && diff > UT_DEG) {
                if (st.utSince == 0) st.utSince = now;
                if (now - st.utSince >= UT_HOLD * 1000) {
                    out.mode = "uturn";
                    if (now - st.utAlert >= UT_EVERY * 1000) { st.utAlert = now; out.events.add(new Ev("uturn", true, "Faites demi-tour.", new long[]{150, 100, 150, 100, 150}, "3")); }
                }
            } else if (diff < 90) st.utSince = 0;
        } else if (Double.isNaN(hd)) st.utSince = 0;
        Turn nx = null;
        for (Turn t : route.turns) if (t.s > along + 1.5) { nx = t; break; }
        if (nx != null) {
            out.hasNext = true; out.nextDist = nx.s - along; out.nextDir = nx.dir; out.nextDelta = nx.delta; out.nextFork = nx.fork;
            String key = String.format(Locale.ROOT, "%.5f,%.5f", nx.pt[0], nx.pt[1]);
            Integer ai = st.ann.get(key);
            int a = ai == null ? 0 : ai;
            double dd = nx.s - along;
            if (out.mode.equals("route")) {
                if (dd <= TURN_NEAR && a < 2) { st.ann.put(key, 2); out.events.add(new Ev("turn-near", false, say(nx.dir), vib(nx.dir), null)); }
                else if (dd <= TURN_FAR && a < 1 && !nx.fork) {
                    st.ann.put(key, 1);
                    out.events.add(new Ev("turn-far", false, "Dans " + (long) Math.max(10, Math.round(dd / 10.0) * 10) + " mètres, " + lbl(nx.dir), null, null));
                }
            }
        }
        return out;
    }
}
