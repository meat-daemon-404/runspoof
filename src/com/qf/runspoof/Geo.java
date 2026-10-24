package com.qf.runspoof;

/**
 * GCJ-02（火星坐标）与 WGS-84 之间的换算。
 *
 * ── 为什么必须有这个类 ──────────────────────────────────────────────
 *
 * 这个问题让「位置还是不对」卡了很久，根因是两个坐标系被当成了同一个：
 *
 *   1. 路线编辑器用的是**高德地图瓦片**。高德全部数据都是 GCJ-02。
 *      用户在瓦片上点出来的每个经纬度，都是 GCJ-02。
 *
 *   2. Android 的 {@code android.location.Location} 约定是 **WGS-84**。
 *      App 拿到 Location 后，如果要把点画到高德地图上，会自己做一次
 *      WGS-84 → GCJ-02 的偏移。
 *
 *   3. 校园跑的有效区域、打卡点判定在服务端做，比对的是 WGS-84。
 *
 * 旧版本把编辑器的 GCJ-02 坐标原样推给 Location。于是：
 *   - App 画地图时又偏移一次 → 轨迹整体偏移约 370 米；
 *   - 服务端按 WGS-84 判区域 → 「不在有效区域内」。
 *
 * 也就是同一个错误在两个地方各表现一次，看起来像两个 bug，其实是
 * 坐标系没换。该偏移在国内大部分地区都是几百米量级，不同地区数值不同，
 * 不能写死，必须现算。
 *
 * ── 处理约定 ────────────────────────────────────────────────────
 *
 * 配置文件（{@link ConfigStore.Config}）里始终保存 **GCJ-02**，
 * 因为它的来源和显示都是高德瓦片；
 * {@link RouteEngine} 在把它编译成路线时调用本类转成 **WGS-84**，
 * 之后位置派发、速度、bearing 全部在 WGS-84 下计算。
 *
 * 换算算法是公开的标准实现（中国国测局加密算法），
 * 只用经纬度做多项式加正弦扰动，不涉及任何密钥。
 */
final class Geo {

    /** 克拉索夫斯基椭球长半轴（GCJ-02 算法沿用该椭球的参数）。 */
    private static final double A = 6378245.0;
    /** 椭球第一偏心率平方。 */
    private static final double EE = 0.00669342162296594323;

    private Geo() {
    }

    /** 粗略判断是否在中国境外。境外不做偏移（GCJ-02 只在境内定义）。 */
    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y
                + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI)
                + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI)
                + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x
                + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI)
                + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI)
                + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    /**
     * WGS-84 → GCJ-02。
     *
     * 输入 {@code {lat, lng}}，返回新的 {@code {lat, lng}}。
     * 原地修改入参容易在并发线程里出错，所以一律返回新数组。
     */
    static double[] wgsToGcj(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0)
                / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0)
                / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /**
     * GCJ-02 → WGS-84。
     *
     * 这个方向没有解析反函数，标准做法是迭代逼近：
     * 把候选值正向偏移后和目标的差回代，几轮就收敛到亚米级。
     *
     * 这里迭代 8 次。实测残差 &lt; 0.01 米，远小于 GPS 本身的精度量级，
     * 多迭代几次的 CPU 开销也只在换路线时发生一次，可以忽略。
     */
    static double[] gcjToWgs(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double wLat = lat;
        double wLng = lng;
        for (int i = 0; i < 8; i++) {
            double[] g = wgsToGcj(wLat, wLng);
            wLat += lat - g[0];
            wLng += lng - g[1];
        }
        return new double[]{wLat, wLng};
    }

    /**
     * 把整条路径从 GCJ-02 转到 WGS-84。
     *
     * 逐点转换再整体返回，不做抽稀 —— 轨迹的角点如果被平滑掉，
     * App 那边可能把拐弯判成「瞬移」。
     */
    static double[][] pathGcjToWgs(double[][] gcjPts) {
        if (gcjPts == null || gcjPts.length == 0) return gcjPts;
        double[][] out = new double[gcjPts.length][2];
        for (int i = 0; i < gcjPts.length; i++) {
            double[] w = gcjToWgs(gcjPts[i][0], gcjPts[i][1]);
            out[i][0] = w[0];
            out[i][1] = w[1];
        }
        return out;
    }
}
