package com.qf.runspoof;

/**
 * 路线引擎：把一个固定点扩展成"随时间真实前进"的轨迹。
 *
 * 为什么必须要有这个：
 * 校园跑的 libswsport.so 里 isValidPoint 是四点相加 0~100 评分，
 * 且服务端会用 SpeedPerTenSec 序列独立重算成绩。
 * 纯固定坐标 + 固定步频会被判成异常轨迹。所以这里要产出：
 *   - 位置随时间平滑前进（不是瞬移）
 *   - 速度与步频自洽（步频由速度反推）
 *   - 首尾形成闭合回路（校园跑通常要求绕圈）
 *
 * ── 本轮修掉的 bug ────────────────────────────────────────────────
 * 1. 旧版用 /data/local/tmp 文件的 lastModified 判断配置是否变化。
 *    那个目录普通 app 写不进去，配置永远写不成功，所以这个判断永远为假，
 *    地图选点从来没有生效过。现在改用 ConfigStore.signature()。
 * 2. 旧版 currentCenter() 返回编译期常量，用户重选中心点后不跟随。已修。
 * 3. 旧版 activePoints / cumDist / totalLen 是三个独立静态字段，
 *    重载配置时逐个赋值，50Hz 的派发线程可能读到「新点 + 旧累计距离」
 *    的中间态，导致 positionAt 插值越界。现在整体换成一个不可变 Route 快照。
 */
final class RouteEngine {

    /**
     * 中心点默认值：**0,0 表示「用户还没画过路线」**。
     *
     * 校园跑类 App 只承认它自己下发的有效区域（通常是某几个体育场），
     * 而每所学校的操场坐标都不一样，所以内置一个具体坐标毫无意义——
     * 只会让用户以为「默认值应该能用」。
     *
     * 第一次使用请在「RunSpoof 路线」界面里逐点画出自己学校的操场，
     * 然后点「保存配置」。画好之后配置里的 centerLat/centerLng
     * 会覆盖这里的默认值。
     */
    private static final double CENTER_LAT = 0.0;
    private static final double CENTER_LNG = 0.0;

    /**
     * 兜底占位坐标：仅用于「用户还没画路线」时生成一个能看见的环，
     * 不是任何真实操场，不要指望它能通过有效区判定。
     */
    private static final double FALLBACK_LAT = 30.000000;
    private static final double FALLBACK_LNG = 120.000000;

    /** 环半径（度）。0.00035 度纬度 ≈ 39 米，正好在田径场范围内。 */
    private static final double RADIUS_DEG = 0.00035;

    /**
     * 闭合折线重采样时每段的采样点数。
     *
     * 8 是在「磨圆折角」和「不增加无谓开销」之间的取值：19 点环 → 152 点，
     * 采样密度足以让相邻点的转向角降到个位数度（约 10 Hz 的空间分辨率）。
     * 调大这个值（如 16）对最大转向角只有边际改善，而 positionAt 的线性扫描
     * 开销翻倍，不划算。
     */
    private static final int SMOOTH_SAMPLES_PER_SEG = 8;

    /**
     * 默认速度（m/s）。
     *
     * ★ 修掉一个会让成绩作废的 bug：旧的 1.8 m/s = 9'15"/km，
     * 比很多学校规定的慢速门限还慢，跑完判无效。
     * 2.4 m/s = 6'57"/km 是典型慢跑姿态，不贴任何区间上下限。
     * 各校的具体有效区间不同，请按 App 规则页自己确认，见 docs/03。
     */
    private static final double SPEED_MPS = 2.4;

    /** 基础步频（SPM）。2.4 m/s 配速下真人典型值约 172。 */
    private static final double BASE_CADENCE = 172.0;

    private static final double EARTH_R = 6371000.0;

    private RouteEngine() {
    }

    // ================================================================ 不可变路线快照

    /** 一条已编译好的路线。所有字段 final，换路线时整体替换引用。 */
    static final class Route {
        final double[][] pts;
        final double[] cum;
        final double total;
        final double centerLat;
        final double centerLng;

        Route(double[][] pts, double centerLat, double centerLng) {
            this.pts = pts;
            this.centerLat = centerLat;
            this.centerLng = centerLng;
            int n = pts.length;
            this.cum = new double[n];
            double acc = 0.0;
            for (int i = 1; i < n; i++) {
                acc += distance(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]);
                cum[i] = acc;
            }
            // 闭合回起点
            this.total = n >= 2
                    ? acc + distance(pts[n - 1][0], pts[n - 1][1], pts[0][0], pts[0][1])
                    : 0.0;
        }
    }

    private static volatile Route route;

    private static long startMs = 0L;
    private static volatile GaitEngine.Session gait;

    private static long lastCfgCheck = 0;
    private static String lastCfgSig = null;

    // ================================================================ 初始化

    static synchronized void ensureInit() {
        if (route == null) {
            startMs = Clock.nowMs();
            ConfigStore.Config cfg = ConfigStore.read();
            lastCfgSig = ConfigStore.signature();
            applyConfig(cfg, false);
        }
        maybeReloadConfig();
    }

    static synchronized void reset() {
        ConfigStore.Config cfg = ConfigStore.read();
        startMs = Clock.nowMs();
        applyConfig(cfg, false);
    }

    /**
     * 每 3 秒最多检查一次配置是否变化。
     *
     * 用文件 mtime + 长度做指纹（ConfigStore.signature()），变了才重新 apply。
     *
     * ★ 这里曾经有一个「看着对、实际不生效」的坑：
     *   ensureInit() 里 if (route == null) 才读配置，之后只靠本方法轮询。
     *   但本方法原先只在 ensureInit() 末尾调用一次，而 route 一旦建好，
     *   之后的 ensureInit() 调用仍然会走到本方法 —— 表面上没问题。
     *   真正的问题在于**指纹一直没变**：
     *   早期版本配置走的是 libxposed remote preferences，
     *   而它在 LSPosed 2.2.1 + API102 上返回空表，签名于是长期相同，
     *   表现为「保存了但位置不动」。
     *   现在改成 /data/local/tmp 文件通道，签名 = mtime:length，
     *   并在 applyConfig 后立刻打印一次，让「到底读到什么」变成可验证的事实。
     */
    private static void maybeReloadConfig() {
        long now = Clock.nowMs();
        if (now - lastCfgCheck < 3000) return;
        lastCfgCheck = now;
        try {
            String sig = ConfigStore.signature();
            if (sig.equals(lastCfgSig)) return;
            lastCfgSig = sig;
            applyConfig(ConfigStore.read(), true);
            android.util.Log.i("RunSpoof", "config reloaded: " + sig);
        } catch (Throwable t) {
            android.util.Log.w("RunSpoof", "config reload failed: " + t);
        }
    }

    /**
     * 应用一份配置。
     *
     * @param restart true 表示路线从头开始（配置变化时）
     */
    private static synchronized void applyConfig(ConfigStore.Config cfg, boolean restart) {
        // ★ 坐标系统一：配置里的经纬度是 GCJ-02（来源是高德瓦片），
        //   而 android.location.Location 和 App 的判区都是 WGS-84。
        //   这里必须先转换再建路线，否则整条轨迹系统性偏移约 370 米，
        //   App 会直接判「不在有效区域内」。详见 Geo 类头注释。
        double lat = cfg.centerLat;
        double lng = cfg.centerLng;
        // centerLat/centerLng 都为 0 表示用户还没画过路线（Config 默认值）。
        // 此时不能真按 (0,0) 生成环——那在大西洋上，会立刻被判「不在有效区域内」。
        // 标成 unset，后面若路径有点就用首点当中心。
        boolean unset = (lat == 0.0 && lng == 0.0);
        if (unset) {
            lat = FALLBACK_LAT;
            lng = FALLBACK_LNG;
        }
        // 兜底占位常量本身是 WGS-84（见其注释），不需要再转；
        // 只有从配置读来的真实坐标才需要转。
        boolean needConvert = !unset;
        double[][] pts;
        if (cfg.path != null && cfg.path.size() >= 2) {
            double[][] gcjPts = cfg.path.toArray(new double[0][]);
            pts = needConvert ? Geo.pathGcjToWgs(gcjPts) : gcjPts;
            if (unset) {
                lat = pts[0][0];
                lng = pts[0][1];
            } else {
                double[] w = Geo.gcjToWgs(lat, lng);
                lat = w[0];
                lng = w[1];
            }
        } else {
            if (needConvert) {
                double[] w = Geo.gcjToWgs(lat, lng);
                lat = w[0];
                lng = w[1];
            }
            // 环是按转换后的中心现生成的，点本身已在 WGS-84 下，不用再转。
            pts = buildRing(lat, lng, degreesForRadius(cfg.radiusM));
        }
        // ★ 改进：折线必须转成光滑曲线再派发。
        //
        // 为什么：位置是按弧长在折线上线性插值的，所以**每个顶点都是一个
        // 瞬时转向**。用户手画的环点很稀，在顶点处每前进一步的最大转向角
        // 可达数十度。真人沿跑道跑，0.1 秒里不可能那样掉头 —— 这个通过
        // Location.getBearing() 和轨迹形状双重可见，是最容易被判"轨迹异常"
        // 的破绽之一。
        //
        // 用闭合二次 B 样条重采样后（默认每段 8 个采样点）：
        //   顶点处的转向角从数十度降到个位数度
        //   转向角分布变均匀，不再有尖峰
        //   环长小幅收缩（把重复点造成的假长度也一并修掉）
        int rawCount = pts.length;
        pts = smoothClosed(pts, SMOOTH_SAMPLES_PER_SEG);
        if (restart) startMs = Clock.nowMs();
        route = new Route(pts, lat, lng);
        // 改进7：海拔基准由配置给出。用户没设过时用兜底值，
        // 但**必须能被设置** —— 写死常量会让所有使用者上报同一个海拔，
        // 而且可能与所在地实际地形相差上千米。
        gait = new GaitEngine.Session(startMs, cfg.speedMps, cfg.cadenceSpm, 10, cfg.altBaseM);
        android.util.Log.i("RunSpoof", "route built: pts=" + rawCount + "->" + pts.length
                + ", center(wgs)=" + lat + "," + lng
                + ", converted=" + needConvert);
    }

    /**
     * 把闭合折线重采样成光滑曲线（闭合二次 B 样条）。
     *
     * 选二次而不是三次：三次 B 样条会**向外鼓**，把环长明显拉长（数个百分点），
     * 轨迹会偏离真实跑道；二次 B 样条过中点，长度只小幅收缩，
     * 形状更贴原折线，同时把角落磨圆。
     *
     * 采样点控制在 2~4 个/秒比较合适：4 m/s 下每 0.4 m 一个点，
     * 相当于 7.5 Hz 的空间分辨率，足以让按弧长插值的结果看不出折角。
     * 点太多没有收益，只会让 positionAt 的线性扫描变慢。
     *
     * @param pts 闭合折线（首尾自动相连，不需要重复首点）
     * @param sps 每段的采样数
     */
    private static double[][] smoothClosed(double[][] pts, int sps) {
        int n = pts.length;
        if (n < 3 || sps < 1) return pts;          // 两点或更少无所谓光滑
        // 转成局部米制坐标再做插值：直接对经纬度做算术平均在经度方向会被
        // cos(lat) 缩放，高纬度下曲线会被压扁。这里按中心纬度换算一次。
        double lat0 = 0, lng0 = 0;
        for (double[] p : pts) {
            lat0 += p[0];
            lng0 += p[1];
        }
        lat0 /= n;
        lng0 /= n;
        double kx = 111320.0 * Math.cos(Math.toRadians(lat0));
        if (Math.abs(kx) < 1e-6) kx = 1e-6;
        double ky = 111320.0;

        double[][] out = new double[n * sps][2];
        int oi = 0;
        for (int i = 0; i < n; i++) {
            double[] p0 = pts[i];
            double[] p1 = pts[(i + 1) % n];
            double[] pm = pts[(i - 1 + n) % n];
            // 上一段的中点 → 本顶点 → 下一段的中点，三点定义一段抛物线
            double m0x = ((pm[1] + p0[1]) / 2 - lng0) * kx;
            double m0y = ((pm[0] + p0[0]) / 2 - lat0) * ky;
            double cx = (p0[1] - lng0) * kx;
            double cy = (p0[0] - lat0) * ky;
            double m1x = ((p0[1] + p1[1]) / 2 - lng0) * kx;
            double m1y = ((p0[0] + p1[0]) / 2 - lat0) * ky;
            for (int s = 0; s < sps; s++) {
                double t = (double) s / sps;
                double u = 1 - t;
                double x = u * u * m0x + 2 * u * t * cx + t * t * m1x;
                double y = u * u * m0y + 2 * u * t * cy + t * t * m1y;
                out[oi][0] = lat0 + y / ky;
                out[oi][1] = lng0 + x / kx;
                oi++;
            }
        }
        return out;
    }

    /** 按中心+半径生成 16 边形闭合环。 */
    private static double[][] buildRing(double lat, double lng, double radiusDeg) {
        final int N = 16;
        double[][] pts = new double[N][2];
        double lngScale = Math.cos(Math.toRadians(lat));
        if (lngScale < 1e-6) lngScale = 1e-6;
        for (int i = 0; i < N; i++) {
            double ang = 2 * Math.PI * i / N;
            pts[i][0] = lat + radiusDeg * Math.sin(ang);
            pts[i][1] = lng + (radiusDeg / lngScale) * Math.cos(ang);
        }
        return pts;
    }

    /** 半径（米）换算成纬度度数。 */
    private static double degreesForRadius(double meters) {
        if (meters < 1) meters = 1;
        if (meters > 5000) meters = 5000;
        return meters / 111320.0;
    }

    // ================================================================ 状态读取

    static synchronized GaitEngine.Session gait() {
        ensureInit();
        return gait;
    }

    /** 已运行秒数。 */
    private static double elapsedSec(long nowMs) {
        double s = (nowMs - startMs) / 1000.0;
        return s < 0 ? 0 : s;
    }

    /** 已行进距离（米），循环回绕。 */
    static double travelledMeters(long nowMs) {
        ensureInit();
        Route r = route;
        GaitEngine.Session g = gait;
        if (r == null || g == null || r.total <= 0) return 0;
        double d = g.distance(elapsedSec(nowMs));
        double m = d % r.total;
        return m < 0 ? m + r.total : m;
    }

    /** 当前坐标。按累计距离在折线上线性插值。 */
    static double[] positionAt(long nowMs) {
        ensureInit();
        Route r = route;
        if (r == null || r.pts.length == 0) return new double[]{FALLBACK_LAT, FALLBACK_LNG};
        if (r.total <= 0 || r.pts.length == 1) {
            return new double[]{r.pts[0][0], r.pts[0][1]};
        }
        double d = travelledMeters(nowMs);
        int n = r.pts.length;
        for (int i = 1; i < n; i++) {
            if (d <= r.cum[i]) {
                double segLen = r.cum[i] - r.cum[i - 1];
                double t = segLen <= 0 ? 0 : (d - r.cum[i - 1]) / segLen;
                return new double[]{
                        r.pts[i - 1][0] + (r.pts[i][0] - r.pts[i - 1][0]) * t,
                        r.pts[i - 1][1] + (r.pts[i][1] - r.pts[i - 1][1]) * t
                };
            }
        }
        // 最后一段：回到起点
        double segLen = r.total - r.cum[n - 1];
        double t = segLen <= 0 ? 0 : (d - r.cum[n - 1]) / segLen;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return new double[]{
                r.pts[n - 1][0] + (r.pts[0][0] - r.pts[n - 1][0]) * t,
                r.pts[n - 1][1] + (r.pts[0][1] - r.pts[n - 1][1]) * t
        };
    }

    /** 当前速度（m/s），带平滑起伏。 */
    static double currentSpeed(long nowMs) {
        ensureInit();
        GaitEngine.Session g = gait;
        return g == null ? SPEED_MPS : g.speed(elapsedSec(nowMs));
    }

    /** 当前瞬时步频（SPM），含慢波动与疲劳衰减。 */
    static double currentCadence(long nowMs) {
        ensureInit();
        GaitEngine.Session g = gait;
        return g == null ? BASE_CADENCE : g.cadence(elapsedSec(nowMs));
    }

    /**
     * 当前**步事件速率**（步/秒）—— 改进6：这是排步事件该用的量，
     * 而不是 {@link #currentCadence(long)} / 60。
     *
     * 为什么两者不同：App 记的 {@code laps.avgCadence} 是「这圈走了多少步 ÷
     * 多少秒」，也就是 {@link #stepsFloat(long)} 的差分；而步事件是模块按某个
     * 速率单独播出去的。旧版按 cadence/60 播事件、按 steps() 积分算相位，
     * 两者因为疲劳项的位置不同而系统性不一致 —— 于是「步数侧」和
     * 「加速度波形侧」在同一个窗口里给出不同的步频。
     *
     * 这是**跨通道可交叉验证**的破绽：App 同时采集 STEP_COUNTER 和
     * ACCELEROMETER，两边算出的步频对不上，比单看一个通道更容易被规则命中。
     * 现在统一到 steps() 的解析导数，两条通道永远同源。
     */
    static double currentStepRate(long nowMs) {
        ensureInit();
        GaitEngine.Session g = gait;
        return g == null ? BASE_CADENCE / 60.0 : g.stepRate(elapsedSec(nowMs));
    }

    /**
     * 当前海拔（米）。
     *
     * ★ 旧版这里是常量 100.0，直接把 {@code Location.getAltitude()} 写死。
     *   后果：App 的 {@code laps_json} 里所有圈都是 {@code endAltAbs=100.0}、
     *   {@code elevationGain=0.0}。而模块接管海拔之前的场次里这两项都是变化的
     *   —— 「全场海拔恒定 + 爬升恒 0」是只有模块在场时才出现的指纹。
     */
    static double currentAltitude(long nowMs) {
        ensureInit();
        GaitEngine.Session g = gait;
        return g == null ? 100.0 : g.altitude(elapsedSec(nowMs));
    }

    /** 当前海拔相对起点的增量（米），对应 App 的 endAltRel。 */
    static double currentAltitudeRelative(long nowMs) {
        ensureInit();
        GaitEngine.Session g = gait;
        return g == null ? 0.0 : g.altitudeRelative(elapsedSec(nowMs));
    }

    /**
     * 水平定位精度（米）。★ 旧版这里是常量 4.0f。
     *
     * 真机在开阔操场上这个值随卫星几何、多径、遮挡在 3~8 m 之间漂；
     * 恒定精度同样是恒定值指纹（App 会把它写进速度和里程的不确定度评估）。
     */
    static double currentHorizontalAccuracy(long nowMs) {
        ensureInit();
        GaitEngine.Session g = gait;
        return g == null ? 4.6 : g.horizontalAccuracy(elapsedSec(nowMs));
    }

    /** 垂直定位精度（米），经验上约为水平精度的 1.5~2 倍。 */
    static double currentVerticalAccuracy(long nowMs) {
        ensureInit();
        GaitEngine.Session g = gait;
        return g == null ? 7.8 : g.verticalAccuracy(elapsedSec(nowMs));
    }

    /** 当前朝向（度）。用于让 Location 的 bearing 自洽。 */
    static float bearingAt(long nowMs) {
        double[] a = positionAt(nowMs - 1000);
        double[] b = positionAt(nowMs);
        return (float) bearing(a[0], a[1], b[0], b[1]);
    }

    /** 累计步数（整数）。 */
    static long stepsAt(long nowMs) {
        return (long) stepsFloat(nowMs);
    }

    /**
     * 累计步数（连续值）：整数部分是步数，小数部分是当前这一步的相位。
     * 加速度波形要按相位查表，必须保留小数。
     */
    static double stepsFloat(long nowMs) {
        ensureInit();
        GaitEngine.Session g = gait;
        return g == null ? 0 : g.steps(elapsedSec(nowMs));
    }

    static double totalLength() {
        ensureInit();
        Route r = route;
        return r == null ? 0 : r.total;
    }

    /** 路线中心（给 MuMu 底层 GPS 对齐用）。跟随用户选择，不再返回常量。 */
    static double[] currentCenter() {
        ensureInit();
        Route r = route;
        return r == null
                ? new double[]{FALLBACK_LAT, FALLBACK_LNG}
                : new double[]{r.centerLat, r.centerLng};
    }

    // ---------------------------------------------------------------- geo math

    private static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360) % 360;
    }
}
