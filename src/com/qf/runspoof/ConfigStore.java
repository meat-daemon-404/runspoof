package com.qf.runspoof;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置读写。
 *
 * ── 为什么改掉旧实现（旧版是一个真 bug）──────────────────────────────
 * 旧版把配置写在 /data/local/tmp/runspoof_route.txt，靠 UI Activity 直接写文件。
 * 实测该目录权限是 `drwxrwx--x shell shell`（0771）：
 *   只有 owner/group 是 shell 的进程能创建文件，普通 app uid 写不进去。
 * 结果：UI 点「保存」永远失败，注入侧永远读默认值 —— 地图选点界面形同虚设。
 *
 * ── 现在的方案 ────────────────────────────────────────────────────
 * 用 libxposed 的 remote preferences 通道：
 *   UI 侧  : getSharedPreferences("runspoof", MODE_PRIVATE) 写入
 *   注入侧 : XposedModule.getRemotePreferences("runspoof") 读取
 * 框架以 root 身份把模块自己的 prefs XML 读出来交给注入进程，
 * 因此跨 uid、跨进程都成立，也不需要任何文件权限。
 *
 * 保留旧的文件通道作为兜底（只在有 root 预先建好可写目录时才有意义），
 * 两条通道都不可用时回落到内置默认值，保证模拟不中断。
 */
final class ConfigStore {

    /** remote preferences 的组名，UI 与注入侧必须一致。 */
    static final String GROUP = "runspoof";

    /** 旧的文件通道（仅兜底）。 */
    static final String FILE = "/data/local/tmp/runspoof_route.txt";

    /**
     * ★ 主通道：直接读模块 App 自己的 SharedPreferences XML。
     *
     * 为什么不用 getRemotePreferences：
     *   实测在 LSPosed 2.2.1 + libxposed API 102 上，它返回的是一个**空表**
     *   （日志 remote=EMPTY），调用本身不报错，于是模块永远读默认值。
     *   表现就是「界面上保存成功、文件也确实写了，但位置一动不动」。
     *
     *   这条直读路线不依赖框架跨进程转发：模块进程以 root 身份可读任意文件，
     *   而 UI 侧把同样的内容额外写一份到 world-readable 的兜底路径。
     */
    static final String MODULE_PREFS_XML =
            "/data/data/com.qf.runspoof/shared_prefs/runspoof.xml";

    /** UI 侧写的世界可读副本，注入进程直接读它，绕开跨 uid 限制。 */
    static final String SHARED_JSON = "/data/local/tmp/runspoof_config.json";

    private static volatile SharedPreferences remote;

    private ConfigStore() {
    }

    /**
     * 注入侧调用：拿到 remote preferences。
     *
     * 用反射而不是直接写 `XposedModule` 类型，是为了让本类也能被 UI 进程加载 ——
     * UI 的 APK 里没有 libxposed 的类（打包时用 --lib，不内嵌）。
     *
     * ★ 注意这里**不能**用「拿一次就永久缓存」的写法。
     *   最初写成 `if (remote != null) return;`，结果：
     *   UI 里保存了新路线，配置文件确实更新了，
     *   但注入侧手里那份 SharedPreferences 是模块启动时取的快照，
     *   之后再也没有重新取，于是永远读到旧值 ——
     *   表现为「保存了，但位置还在老地方」，而日志里 remotePrefs=true 一切正常。
     *
     *   现在每次读配置前都重新取一次。getRemotePreferences 本身很轻
     *   （框架侧按包名查一次表），比"读到过期配置"划算得多。
     */
    private static Object moduleRef;

    static void attach(Object module) {
        if (module != null) moduleRef = module;
    }

    /** 重新向框架取一次 remote preferences。取不到就返回已缓存的那份。 */
    private static SharedPreferences freshRemote() {
        Object m = moduleRef;
        if (m != null) {
            try {
                java.lang.reflect.Method meth = m.getClass()
                        .getMethod("getRemotePreferences", String.class);
                Object p = meth.invoke(m, GROUP);
                if (p instanceof SharedPreferences) {
                    remote = (SharedPreferences) p;
                } else {
                    android.util.Log.w("RunSpoof", "getRemotePreferences returned "
                            + (p == null ? "null" : p.getClass().getName()));
                }
            } catch (Throwable t) {
                // ★ 这里不能静默吞掉。反射调用失败时如果什么都不打，
                //   表现就是"读到空配置"而完全看不出原因。
                android.util.Log.w("RunSpoof", "getRemotePreferences failed: " + t, t);
            }
        } else {
            android.util.Log.w("RunSpoof", "moduleRef is null, attach() not called");
        }
        return remote;
    }

    static boolean hasRemote() {
        SharedPreferences p = freshRemote();
        if (p == null) return false;
        // 只有真的能读出键才算"通了"，否则就是一个空壳对象
        try {
            return p.getAll() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 调试用：把 remote prefs 里到底有什么打出来。 */
    static String describeRemote() {
        SharedPreferences p = freshRemote();
        if (p == null) return "remote=null";
        try {
            java.util.Map<String, ?> all = p.getAll();
            if (all == null) return "remote.getAll()=null";
            if (all.isEmpty()) return "remote=EMPTY";
            StringBuilder sb = new StringBuilder("remote keys=");
            for (String k : all.keySet()) sb.append(k).append(',');
            return sb.toString();
        } catch (Throwable t) {
            return "remote describe failed: " + t;
        }
    }

    /** 一条路线：中心点 + 半径（米）+ 速度 + 步频。 */
    static final class Config {
        /**
         * 中心点：**0,0 表示「还没设过」**，不是某个真实位置。
         *
         * 每所学校的操场坐标都不一样，内置一个具体坐标只会误导用户
         * 以为「默认值应该能用」。所以默认留 0，强制用户自己在编辑器里画一遍。
         * 读到 0,0 时 RouteEngine 会用路径首点或兜底环，不会真的跑到几内亚湾。
         */
        double centerLat = 0.0;
        double centerLng = 0.0;
        double radiusM = 39.0;
        /**
         * 默认速度 2.4 m/s ≈ 6'57"/km。
         *
         * ★ 这里修掉一个会直接导致成绩作废的 bug：
         * 旧的 1.8 m/s 等于 9'15"/km，比很多学校规定的慢速门限还慢，
         * 跑完会被判无效。2.4 m/s 是典型慢跑姿态，不贴任何区间上下限。
         *
         * 各校的有效配速区间不同，请按 App 规则页自己确认，见 docs/03。
         */
        double speedMps = 2.4;
        /** 2.4 m/s 下真人典型步频约 172 SPM，与速度自洽。 */
        double cadenceSpm = 172.0;
        /** 自定义路径点（可选）。为空时用环形。 */
        List<double[]> path = new ArrayList<>();

        /**
         * 改进7：海拔基准（米）。
         *
         * ★ 为什么必须可配置：旧版把 {@code Location.getAltitude()} 写死成 100.0，
         *   于是 App 记录的每一圈 {@code endAltAbs} 都是 100.0、
         *   {@code elevationGain} 都是 0.0。实测对照很清楚 ——
         *   同一账号在模块接管之前（App 拿真实 GNSS）的场次里，这个值在
         *   **km 量级且逐圈变化**，与当地地形量级相符。
         *
         *   两层破绽：(a) 逐圈恒定（真人不可能每公里爬升恰好 0 m）；
         *   (b) 绝对量级可能与所在地相差上千米。
         *   更要紧的是：写死常量会让**所有使用者上报同一个海拔**，
         *   这是可跨账号聚合的全局指纹。
         *
         *   NaN 表示「没设过」，此时用 {@link GaitEngine#DEFAULT_ALT_BASE_M} 兜底。
         *   各校海拔差异极大，必须按自己所在位置填，见 docs/03。
         */
        double altBaseM = Double.NaN;

        // ── 安全边界 ────────────────────────────────────────────────
        // 使用者可以在界面上改速度/步频，但手填一个离谱的值跑完也是白跑。
        // 这里做钳制而不是报错 —— 静默修正比让用户困惑更好。
        //
        // 配速 = 1000 / speed / 60 分/公里。1.9 m/s = 8'46"，5.4 m/s = 3'05"，
        // 覆盖从快走到快跑的常见范围；各家学校的具体有效区间请自行在 App 里确认。
        static final double SPEED_MIN = 1.9;
        static final double SPEED_MAX = 5.4;

        // 常见人类步频范围，两端留余量
        static final double CADENCE_MIN = 45.0;
        static final double CADENCE_MAX = 290.0;

        // 半径太小会绕圈过密（App 可能判轨迹异常），太大容易出有效区域
        static final double RADIUS_MIN = 5.0;
        static final double RADIUS_MAX = 200.0;

        // 海拔的合法区间（米）。地球陆地范围约 −430 ~ 8849，这里留足余量。
        // 注意允许负值：确有低于海平面的城市。
        static final double ALT_MIN = -500.0;
        static final double ALT_MAX = 9000.0;

        /** 把字段钳制到合法区间。UI 保存和注入侧读取都要过一遍。 */
        Config clamp() {
            speedMps = clampD(speedMps, SPEED_MIN, SPEED_MAX, 2.4);
            cadenceSpm = clampD(cadenceSpm, CADENCE_MIN, CADENCE_MAX, 172.0);
            radiusM = clampD(radiusM, RADIUS_MIN, RADIUS_MAX, 39.0);
            // 海拔：NaN 保持 NaN（表示「没设过」，由 GaitEngine 兜底），
            // 设过的值必须落在合法区间内。
            if (!Double.isNaN(altBaseM)) {
                altBaseM = clampD(altBaseM, ALT_MIN, ALT_MAX, Double.NaN);
            }
            if (centerLat < -90 || centerLat > 90) centerLat = 0.0;
            if (centerLng < -180 || centerLng > 180) centerLng = 0.0;
            return this;
        }

        private static double clampD(double v, double lo, double hi, double def) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return def;
            if (v < lo) return lo;
            if (v > hi) return hi;
            return v;
        }
        /** 当前速度对应的配速（分/公里），给界面显示用。 */
        double paceMinPerKm() {
            return speedMps <= 0.01 ? 0 : 1000.0 / speedMps / 60.0;
        }

        /** 把配速格式化成 5'33" 这种样子。 */
        String paceText() {
            double p = paceMinPerKm();
            if (p <= 0) return "--'--\"";
            int m = (int) p;
            int s = (int) Math.round((p - m) * 60);
            if (s == 60) { m++; s = 0; }
            return m + "'" + (s < 10 ? "0" + s : String.valueOf(s)) + "\"";
        }

        String pathString() {
            StringBuilder sb = new StringBuilder();
            for (double[] p : path) {
                if (sb.length() > 0) sb.append(';');
                sb.append(p[0]).append(',').append(p[1]);
            }
            return sb.toString();
        }

        void setPathString(String csv) {
            path.clear();
            if (csv == null || csv.isEmpty()) return;
            for (String seg : csv.split(";")) {
                String[] xy = seg.split(",");
                if (xy.length != 2) continue;
                try {
                    path.add(new double[]{
                            Double.parseDouble(xy[0].trim()),
                            Double.parseDouble(xy[1].trim())
                    });
                } catch (Throwable ignored) {
                }
            }
        }

        String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append("centerLat=").append(centerLat).append('\n');
            sb.append("centerLng=").append(centerLng).append('\n');
            sb.append("radiusM=").append(radiusM).append('\n');
            sb.append("speedMps=").append(speedMps).append('\n');
            sb.append("cadenceSpm=").append(cadenceSpm).append('\n');
            // 没设过就写空字符串，而不是写 NaN —— NaN 在部分文本通道里
            // 会被读成 0，那会让海拔基准静默变成 0 m（海平面）。
            if (!Double.isNaN(altBaseM)) {
                sb.append("altBaseM=").append(altBaseM).append('\n');
            }
            for (double[] p : path) {
                sb.append("point=").append(p[0]).append(',').append(p[1]).append('\n');
            }
            return sb.toString();
        }

        static Config parse(String text) {
            Config c = new Config();
            c.path.clear();
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "centerLat":
                            c.centerLat = Double.parseDouble(v);
                            break;
                        case "centerLng":
                            c.centerLng = Double.parseDouble(v);
                            break;
                        case "radiusM":
                            c.radiusM = Double.parseDouble(v);
                            break;
                        case "speedMps":
                            c.speedMps = Double.parseDouble(v);
                            break;
                        case "cadenceSpm":
                            c.cadenceSpm = Double.parseDouble(v);
                            break;
                        case "altBaseM":
                            c.altBaseM = Double.parseDouble(v);
                            break;
                        case "point": {
                            String[] xy = v.split(",");
                            if (xy.length == 2) {
                                c.path.add(new double[]{
                                        Double.parseDouble(xy[0].trim()),
                                        Double.parseDouble(xy[1].trim())
                                });
                            }
                            break;
                        }
                        case "path":
                            // 兼容两种写法：整行 CSV（UI 的 serialize 用这个），
                            // 以及逐行 point=。两条通道写出来的文件格式不一样，
                            // 解析端必须都吃得下，否则「读到了中心点但没有路线」。
                            c.setPathString(v);
                            break;
                        default:
                            break;
                    }
                } catch (Throwable ignored) {
                }
            }
            return c;
        }
    }

    // ================================================================ 读

    /**
     * 注入侧读配置。优先级：
     *   1. 共享 JSON（UI 主动写一份到 /data/local/tmp，最可靠）
     *   2. 直接解析模块的 SharedPreferences XML
     *   3. remote preferences（框架通道，本环境实测返回空表）
     *   4. 旧文件通道 / 默认值
     */
    static Config read() {
        // ★ 文件通道排第一：/data/local/tmp 下的世界可读写文件是当前唯一
        //   在「注入进程（目标 app uid）」里真正可用的通道。
        //   另两条（模块 prefs XML、remote preferences）在这里都读不到，见下方注释。
        Config fc = readFileChannel();
        if (fc != null) {
            android.util.Log.i("RunSpoof", "cfg[0] file channel OK, pathPts=" + fc.path.size());
            return fc;
        }

        Config c = readSharedJson();
        android.util.Log.i("RunSpoof", "cfg[1] sharedJson=" + (c != null));
        if (c != null) return c;

        File pf = new File(MODULE_PREFS_XML);
        android.util.Log.i("RunSpoof", "cfg[2] xml exists=" + pf.exists()
                + " canRead=" + pf.canRead() + " len=" + pf.length()
                + " parentX=" + new File(pf.getParentFile().getParent()).canExecute());
        c = readPrefsXml(pf);
        android.util.Log.i("RunSpoof", "cfg[2] xml parsed=" + (c != null));
        if (c != null) return c;

        SharedPreferences p = freshRemote();
        if (p != null) {
            try {
                String lat = p.getString("centerLat", null);
                if (lat != null) return fromPrefs(p);
            } catch (Throwable ignored) {
            }
        }
        return readFile();
    }

    /** 读 UI 写的共享文本（key=value 行格式，与 Config.serialize 一致）。 */
    private static Config readSharedJson() {
        try {
            File f = new File(SHARED_JSON);
            if (!f.exists()) return null;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            String txt = new String(buf, 0, n, "UTF-8");
            if (!txt.contains("centerLat")) return null;
            return Config.parse(txt).clamp();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 直接解析 SharedPreferences 的 XML。
     *
     * 只认 <string> 节点。我们的配置全部写成了字符串，所以够用；
     * 不引入 XmlPullParser 之外的依赖，也不用反射读别人的进程。
     */
    private static Config readPrefsXml(File f) {
        try {
            if (!f.exists()) return null;
            javax.xml.parsers.DocumentBuilderFactory dbf =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(f);
            org.w3c.dom.NodeList nodes = doc.getElementsByTagName("string");
            java.util.Map<String, String> kv = new java.util.HashMap<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) nodes.item(i);
                String name = e.getAttribute("name");
                if (name != null && !name.isEmpty()) {
                    kv.put(name, e.getTextContent());
                }
            }
            if (kv.isEmpty()) return null;
            Config c = new Config();
            c.centerLat = parseDouble(kv.get("centerLat"), c.centerLat);
            c.centerLng = parseDouble(kv.get("centerLng"), c.centerLng);
            c.radiusM = parseDouble(kv.get("radiusM"), c.radiusM);
            c.speedMps = parseDouble(kv.get("speedMps"), c.speedMps);
            c.cadenceSpm = parseDouble(kv.get("cadenceSpm"), c.cadenceSpm);
            c.altBaseM = parseDouble(kv.get("altBaseM"), c.altBaseM);
            c.setPathString(kv.get("path"));
            return c.clamp();
        } catch (Throwable t) {
            android.util.Log.w("RunSpoof", "cfg xml parse failed: " + t);
            return null;
        }
    }

    /**
     * UI 侧读配置。
     *
     * ★ 必须和注入侧走同一条通道，否则会出现「编辑器显示默认值 0 个点，
     *   但目标 App 里跑的还是旧路线」这种鬼故事 —— 因为两边读的压根不是
     *   同一份数据：注入侧读 /data/local/tmp 文件，UI 原来只读自己的
     *   SharedPreferences，重装之后 SP 被清空而 tmp 文件还在。
     *
     * 顺序：文件通道 → 自己的 SP → 默认值。
     */
    static Config readLocal(Context ctx) {
        Config fc = readFileChannel();
        if (fc != null) {
            // 顺手把 SP 也补齐，避免下次读不到文件时又退回默认值
            writePrefsOnly(ctx, fc);
            return fc;
        }
        try {
            return fromPrefs(ctx.getSharedPreferences(GROUP, Context.MODE_PRIVATE));
        } catch (Throwable t) {
            return new Config();
        }
    }

    /** 只写 SharedPreferences，不动文件（readLocal 回填用）。 */
    private static void writePrefsOnly(Context ctx, Config c) {
        try {
            android.content.SharedPreferences.Editor e =
                    ctx.getSharedPreferences(GROUP, Context.MODE_PRIVATE).edit()
                            .putString("centerLat", Double.toString(c.centerLat))
                            .putString("centerLng", Double.toString(c.centerLng))
                            .putString("radiusM", Double.toString(c.radiusM))
                            .putString("speedMps", Double.toString(c.speedMps))
                            .putString("cadenceSpm", Double.toString(c.cadenceSpm));
            if (!Double.isNaN(c.altBaseM)) {
                e.putString("altBaseM", Double.toString(c.altBaseM));
            } else {
                e.remove("altBaseM");
            }
            e.putString("path", c.pathString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static Config fromPrefs(SharedPreferences p) {
        Config c = new Config();
        c.centerLat = parseDouble(p.getString("centerLat", null), c.centerLat);
        c.centerLng = parseDouble(p.getString("centerLng", null), c.centerLng);
        c.radiusM = parseDouble(p.getString("radiusM", null), c.radiusM);
        c.speedMps = parseDouble(p.getString("speedMps", null), c.speedMps);
        c.cadenceSpm = parseDouble(p.getString("cadenceSpm", null), c.cadenceSpm);
        c.altBaseM = parseDouble(p.getString("altBaseM", null), c.altBaseM);
        c.setPathString(p.getString("path", ""));
        // ★ 统一在这里钳制：无论是 UI 手填还是别处写入，
        //   出边界的值都不该流到路线/步态引擎里。
        return c.clamp();
    }

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 从 /data/local/tmp 读配置。
     *
     * 为什么这条是主通道：
     *   Android 10+ 对每个 app 做了数据目录隔离 —— 在目标 app 的 mount namespace 里，
     *   /data/data 是 tmpfs，只挂了它自己那一份。所以模块进程**根本看不到**
     *   /data/data/com.qf.runspoof/shared_prefs/，readPrefsXml 永远 exists=false。
     *   同理 remote preferences 在这套框架上返回空表。
     *   唯一在两侧都可见的就是 /data/local/tmp 下的文件（由 root 预建并 chmod 666）。
     *
     * 文件不存在时返回 null，让调用方继续往下试。
     */
    private static Config readFileChannel() {
        try {
            File f = new File(FILE);
            if (!f.exists() || !f.canRead() || f.length() <= 0) return null;
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            if (off <= 0) return null;
            String txt = new String(buf, 0, off, "UTF-8");
            if (!txt.contains("centerLat")) return null;
            Config c = Config.parse(txt).clamp();
            c.path = dedupe(c.path);
            return c;
        } catch (Throwable t) {
            android.util.Log.w("RunSpoof", "cfg file channel failed: " + t);
            return null;
        }
    }

    /** 去掉相邻重复点（UI 拖拽容易产生），避免路线里出现零长度段。 */
    private static List<double[]> dedupe(List<double[]> src) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : src) {
            if (out.isEmpty()) {
                out.add(p);
                continue;
            }
            double[] last = out.get(out.size() - 1);
            if (Math.abs(last[0] - p[0]) > 1e-9 || Math.abs(last[1] - p[1]) > 1e-9) {
                out.add(p);
            }
        }
        return out;
    }
    private static Config readFile() {
        try {
            File f = new File(FILE);
            if (!f.exists()) return new Config();
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            if (n <= 0) return new Config();
            return Config.parse(new String(buf, 0, n, "UTF-8"));
        } catch (Throwable t) {
            return new Config();
        }
    }

    // ================================================================ 写

    /** UI 侧写配置：写进模块自己的 SharedPreferences，并开放读权限给注入进程。 */
    static boolean writeUi(Context ctx, Config c) {
        boolean ok = false;
        try {
            SharedPreferences p = ctx.getSharedPreferences(GROUP, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor e = p.edit()
                    .putString("centerLat", Double.toString(c.centerLat))
                    .putString("centerLng", Double.toString(c.centerLng))
                    .putString("radiusM", Double.toString(c.radiusM))
                    .putString("speedMps", Double.toString(c.speedMps))
                    .putString("cadenceSpm", Double.toString(c.cadenceSpm));
            if (!Double.isNaN(c.altBaseM)) {
                e.putString("altBaseM", Double.toString(c.altBaseM));
            } else {
                e.remove("altBaseM");
            }
            ok = e.putString("path", c.pathString()).commit();
            // ★ commit() 之后立刻放宽权限，注入侧才读得到。
            grantReadToOthers(ctx);
        } catch (Throwable ignored) {
        }
        // 文件通道才是注入侧真正读得到的那条，必须写
        boolean fileOk = writeFile(c);
        return ok && fileOk;
    }

    /**
     * 让"别的 uid"能读到本模块的配置文件。
     *
     * 为什么必须这么做：
     *   注入进目标 App 的模块代码跑在**目标 App 的 uid**（10052）里，
     *   而配置文件的属主是模块自己的 uid（如 u0_a75），
     *   默认权限 rw-rw---- 导致注入侧 open 失败 —— 表现就是
     *   「界面上保存了、文件也更新了，但路线始终是默认的那圈」。
     *
     *   只动三个权限位，不需要 root：
     *     App 数据目录  711  （o+x，让别的 uid 能进来）
     *     shared_prefs  711  （同上；通常本来就是 drwxrwx--x）
     *     配置文件      644  （o+r，真正的内容）
     */
    private static void grantReadToOthers(Context ctx) {
        try {
            File dataDir = new File(ctx.getApplicationInfo().dataDir);
            dataDir.setExecutable(true, false);

            File prefsDir = new File(dataDir, "shared_prefs");
            prefsDir.setExecutable(true, false);

            File xml = new File(prefsDir, GROUP + ".xml");
            xml.setReadable(true, false);
            xml.setExecutable(false, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 写文件通道。
     *
     * ★ 这个方法的返回值很重要：/data/local/tmp 的权限是 drwxrwx--x（0771），
     *   属主 shell。模块 UI 跑在自己的 app uid 下，**新建不了文件**，
     *   但可以写一个已经存在的文件（如果它的权限放开了）。
     *   所以文件必须由 root 预先创建：
     *
     *       adb shell "touch /data/local/tmp/runspoof_route.txt;
     *                  chmod 666 /data/local/tmp/runspoof_route.txt"
     *
     *   没做这一步时，界面上按「保存配置」会显示"已保存"（SharedPreferences
     *   那半确实成功了），但注入侧读不到 —— 表现就是「保存了但位置不动」。
     *   所以这里把结果返回出去，让 UI 能明确告警。
     */
    private static boolean writeFile(Config c) {
        try {
            File f = new File(FILE);
            if (!f.exists()) return false;          // app uid 建不了，别白试
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(c.serialize().getBytes("UTF-8"));
            out.close();
            f.setReadable(true, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 文件通道现在可用吗？
     * UI 用它决定要不要提示用户去跑那条 root 命令。
     */
    static boolean fileReady() {
        try {
            File f = new File(FILE);
            if (f.exists() && f.canRead() && f.canWrite()) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 给 UI 显示用的一句话说明。 */
    static String fileHint() {
        return "adb shell \"touch " + FILE + "; chmod 666 " + FILE + "\"";
    }

    // ================================================================ 变更检测

    /**
     * 配置指纹。注入侧每 3 秒比一次，变了就重载路线。
     *
     * 旧版用文件 mtime 判断，换了存储通道后不再适用，改成比对内容指纹。
     *
     * ★ 必须用 freshRemote()：如果指纹基于过期快照来算，
     *   UI 保存后指纹不会变，maybeReloadConfig 就永远不会 applyConfig，
     *   表现为「保存了但位置不动」。
     */
    static String signature() {
        // 文件通道排第一，理由同 read()：这是唯一真正可用的通道。
        try {
            File f = new File(FILE);
            if (f.exists() && f.canRead()) {
                return "file:" + f.lastModified() + ':' + f.length();
            }
        } catch (Throwable ignored) {
        }
        SharedPreferences p = freshRemote();
        if (p != null) {
            try {
                return p.getString("centerLat", "-") + '|'
                        + p.getString("centerLng", "-") + '|'
                        + p.getString("radiusM", "-") + '|'
                        + p.getString("speedMps", "-") + '|'
                        + p.getString("cadenceSpm", "-") + '|'
                        + p.getString("path", "");
            } catch (Throwable ignored) {
            }
        }
        try {
            File f = new File(FILE);
            if (f.exists()) return "file:" + f.lastModified() + ':' + f.length();
        } catch (Throwable ignored) {
        }
        return "none";
    }
}
