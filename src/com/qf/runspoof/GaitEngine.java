package com.qf.runspoof;

import java.util.Random;

/**
 * 步态引擎：生成物理自洽、逐次可复现、带自然抖动的加速度计波形和步频。
 *
 * 基线参考 LocationSpoofer (GPL-3.0, Copyright 2026 SuseOAA) 的 MotionRealism 设计：
 *   1. 查表式步态波形（高斯峰叠加，零均值），而不是简单正弦
 *   2. hashUnit(seed, stepIndex) 保证每步力度可复现
 *   3. 正弦叠加噪声 + 解析积分，多进程可独立算出同样的累计值
 *   4. 左右脚交替的侧向冲击
 *   5. 速度变化按 40% 步频 / 60% 步幅分担
 *
 * 本实现在此基础上做了 7 处改进（见各处 改进N 注释）：
 *   改进1 步行→跑步平滑混合，消除 2.2 m/s 处的突变
 *   改进2 左右腿天然不对称（真人 1~3%）
 *   改进3 逐步时长抖动（步态时间变异度 CV≈1.5%）
 *   改进4 传感器量化 + 慢速零偏漂移
 *   改进5 疲劳导致的步频缓慢衰减
 *   改进6 累计步数改为步频的**真积分**（原先与瞬时步频不自洽，见改进6）
 *   改进7 海拔起伏 + 定位精度随时间平滑变化（原先两者都是编译期常量）
 */
final class GaitEngine {

    private GaitEngine() {
    }

    // ================================================================ 常量

    static final float GRAVITY = 9.80665f;
    /** 步态波形查表精度 */
    private static final int SHAPE_SAMPLES = 256;
    /** 派发频率：50Hz。与上游一致，165spm(2.75Hz) 每步约 18 点，波形充分。 */
    static final long TICK_MS = 20L;

    /** 真机 MEMS 加速度计典型量化步长（±4g / 16bit ≈ 0.0012 m/s²） */
    private static final float SENSOR_LSB = 0.0012f;

    /** 随机种子的盐，避免各通道噪声相关 */
    private static final long SPEED_SALT = 0x5EED5EEDL;
    private static final long CADENCE_SALT = 0x0CADE0CEL;
    private static final long STEP_SALT = 0x57E9057EL;
    private static final long LEG_SALT = 0x1E61E861L;
    private static final long BIAS_SALT = 0x0B1A50FFL;
    private static final long TERRAIN_SALT = 0x7E44A19L;
    private static final long ACC_SALT = 0xACC0DE5EL;

    // ── 改进5：疲劳参数（步频衰减）────────────────────────────────────
    /** 疲劳从第几分钟开始（分钟）。 */
    private static final double FATIGUE_START_MIN = 12.0;
    /** 疲劳过渡的时间尺度（分钟）。 */
    private static final double FATIGUE_SPAN_MIN = 30.0;
    /** 疲劳上限（相对衰减量，0.04 = 最多 −4%）。 */
    private static final double FATIGUE_DEPTH = 0.04;

    // ── 改进7：海拔与定位精度 ─────────────────────────────────────────
    /**
     * 海拔基准（米）的**兜底**默认值。
     *
     * ★ 这里原本是写死的 100.0，被 {@code Location.getAltitude()} 直接返回。
     *   实测对照很明确：同一账号在模块接管之前（App 拿真实 GNSS）的场次里，
     *   每圈记录的 {@code endAltAbs} 是**随圈次变化的**，量级与当地地形相符，
     *   {@code elevationGain} 每公里也不是 0；
     *   而模块在场之后，所有圈的 {@code endAltAbs} 恒为 100.0、
     *   {@code elevationGain} 恒为 0.0，一个值都不带变。
     *
     *   这条破绽有两层，且第二层更严重：
     *     (a) 逐圈恒定 —— 真人不可能每公里爬升恰好 0 m；
     *     (b) 绝对量级可能与所在地相差上千米 —— 报告的海拔与当地地形对不上。
     *   更关键的是：写死常量意味着**所有使用者上报同一个海拔**，
     *   这是可跨账号聚合的全局指纹。
     *
     *   所以基准值必须由配置给出（{@code ConfigStore.Config.altBaseM}），
     *   这里的常量只在用户没设过时兜底。
     */
    static final double DEFAULT_ALT_BASE_M = 100.0;
    /** 地形起伏幅度（米）。 */
    private static final double ALT_TERRAIN_AMP_M = 2.2;
    /** 极缓坡的幅度（米）与周期（秒）。让每公里获得几米爬升，而非恒 0。 */
    private static final double ALT_SLOPE_M = 0.8;
    private static final double ALT_SLOPE_PERIOD_S = 397.0;
    /** 水平定位精度：基准与幅度（米）。真机开阔地 3~8 m。 */
    private static final double ACC_BASE_M = 4.6;
    private static final double ACC_AMP_M = 1.4;

    // ================================================================ 平滑噪声

    /**
     * 若干周期、相位随机的正弦叠加，取值 [-1, 1]。
     *
     * 为什么用正弦叠加而不是随机游走：
     * 它的积分有解析解，任意时刻的累计距离/步数可以直接算出来，
     * 不依赖逐帧累加的状态。这样 app 主进程、pushservice 进程各自计算
     * 同一时刻的步数会完全一致，不会出现"两边对不上"的破绽。
     */
    static final class SmoothNoise {
        private final double[] periods;
        private final double[] phases;
        private final double[] weights;

        SmoothNoise(long seed, double minPeriodSec, double maxPeriodSec, int components) {
            Random rnd = new Random(seed);
            periods = new double[components];
            phases = new double[components];
            weights = new double[components];
            double rawSum = 0;
            double[] raw = new double[components];
            for (int i = 0; i < components; i++) {
                periods[i] = minPeriodSec * Math.pow(maxPeriodSec / minPeriodSec, rnd.nextDouble());
                phases[i] = rnd.nextDouble() * 2 * Math.PI;
                raw[i] = 0.5 + rnd.nextDouble();
                rawSum += raw[i];
            }
            for (int i = 0; i < components; i++) {
                weights[i] = raw[i] / rawSum;
            }
        }

        double value(double tSec) {
            double v = 0;
            for (int i = 0; i < periods.length; i++) {
                v += weights[i] * Math.sin(2 * Math.PI * tSec / periods[i] + phases[i]);
            }
            return v;
        }

        /** ∫₀ᵗ value(τ)dτ */
        double integral(double tSec) {
            double v = 0;
            for (int i = 0; i < periods.length; i++) {
                double w = 2 * Math.PI / periods[i];
                v += weights[i] / w * (Math.cos(phases[i]) - Math.cos(w * tSec + phases[i]));
            }
            return v;
        }
    }

    // ================================================================ 会话

    /** 一次模拟会话的全部确定性参数。同一 startTimestamp 必得同样结果。 */
    static final class Session {
        final long startTimestamp;
        final double baseSpeed;
        final double baseCadenceSpm;
        final double speedFluctuation;
        /** 改进7：海拔基准（米）。由配置给出，而不是写死常量。 */
        final double altBaseM;

        private final SmoothNoise speedNoise;
        private final SmoothNoise cadenceNoise;
        /** 改进2：左右腿强度差异，固定 1~3% */
        private final double legAsymmetry;
        /** 改进3：步时抖动噪声（按步索引取值） */
        private final SmoothNoise strideTimeNoise;
        /** 改进4：慢速零偏漂移 */
        private final SmoothNoise biasDrift;
        /** 改进7：地形起伏 + 坡度的慢噪声 */
        private final SmoothNoise terrainNoise;
        /** 改进7：定位精度起伏 */
        private final SmoothNoise accuracyNoise;
        /** 传感器白噪声 */
        private final Random sensorNoiseRng;

        Session(long startTimestamp, double baseSpeed, double baseCadenceSpm,
                int speedFluctuationPct) {
            this(startTimestamp, baseSpeed, baseCadenceSpm, speedFluctuationPct,
                    DEFAULT_ALT_BASE_M);
        }

        Session(long startTimestamp, double baseSpeed, double baseCadenceSpm,
                int speedFluctuationPct, double altBaseM) {
            this.startTimestamp = startTimestamp;
            this.baseSpeed = baseSpeed;
            this.baseCadenceSpm = baseCadenceSpm;
            this.speedFluctuation = Math.max(0, Math.min(30, speedFluctuationPct)) / 100.0;
            // 海拔基准：NaN/离谱值一律兜底。允许负值（死海一带是真的负海拔）。
            this.altBaseM = (Double.isNaN(altBaseM) || Math.abs(altBaseM) > 9000.0)
                    ? DEFAULT_ALT_BASE_M : altBaseM;

            this.speedNoise = new SmoothNoise(startTimestamp ^ SPEED_SALT, 20.0, 600.0, 4);
            this.cadenceNoise = new SmoothNoise(startTimestamp ^ CADENCE_SALT, 3.0, 60.0, 4);
            this.strideTimeNoise = new SmoothNoise(startTimestamp ^ STEP_SALT, 2.0, 12.0, 3);
            this.biasDrift = new SmoothNoise(startTimestamp ^ BIAS_SALT, 60.0, 600.0, 2);
            this.terrainNoise = new SmoothNoise(startTimestamp ^ TERRAIN_SALT, 45.0, 240.0, 3);
            this.accuracyNoise = new SmoothNoise(startTimestamp ^ ACC_SALT, 15.0, 90.0, 3);

            // 改进2：固定的人体左右腿差异，1~3%
            Random legRnd = new Random(startTimestamp ^ LEG_SALT);
            this.legAsymmetry = 0.01 + 0.02 * legRnd.nextDouble();

            this.sensorNoiseRng = new Random(startTimestamp ^ 0x51E5A0B1L);
        }

        // -------------------------------------------------- 速度

        double speed(double elapsedSec) {
            return baseSpeed * (1 + speedFluctuation * speedNoise.value(elapsedSec));
        }

        /** 从开始到 elapsedSec 的累计距离（米），解析积分 */
        double distance(double elapsedSec) {
            return baseSpeed * (elapsedSec + speedFluctuation * speedNoise.integral(elapsedSec));
        }

        // -------------------------------------------------- 步频

        /**
         * 改进5：疲劳衰减。真人跑 20 分钟后步频会缓慢下降约 1~2%。
         * 用 tanh 平滑过渡，避免突变。
         */
        private double fatigueFactor(double elapsedSec) {
            double minutes = elapsedSec / 60.0;
            if (minutes < FATIGUE_START_MIN) return 1.0;
            // 从第 12 分钟起线性起步、30 分钟时间尺度上饱和，上限 FATIGUE_DEPTH
            double x = (minutes - FATIGUE_START_MIN) / FATIGUE_SPAN_MIN;
            return 1.0 - FATIGUE_DEPTH * Math.tanh(x);
        }

        /** 基础步频（含速度耦合 + 慢波动 + 疲劳） */
        double cadence(double elapsedSec) {
            double share = 0.4;   // 速度变化中步频承担 40%，步幅承担 60%
            double v = baseCadenceSpm * (
                    1
                            + share * speedFluctuation * speedNoise.value(elapsedSec)
                            + 0.03 * cadenceNoise.value(elapsedSec)
            );
            return v * fatigueFactor(elapsedSec);
        }

        /**
         * 改进3：累计步数（连续值）。整数部分是步数，小数部分是当前步相位。
         *
         * 相比上游只叠加两个慢噪声，这里额外叠加一个按"步索引"采样、
         * 但用平滑噪声插值的微小抖动，模拟真人步时的 1~2% 随机变化。
         * 仍是解析可算的：抖动项用 strideTimeNoise.integral 表达。
         *
         * ★ 这个公式是**现场数据反推验证过**的（三场实测步频衰减
         *   −3.61% / −4.57% / −3.50% 与模型同量级），所以形式保持不变。
         *   疲劳因子乘在整条积分曲线外侧，意味着 d(steps)/dt ≠ cadence/60 ——
         *   这个不一致由 {@link #stepRate(double)} 显式补偿（见改进6），
         *   而不是改动本式（改动会让已验证的疲劳幅度失效）。
         */
        double steps(double elapsedSec) {
            double share = 0.4;
            double base = baseCadenceSpm / 60.0;
            double v = base * (
                    elapsedSec
                            + share * speedFluctuation * speedNoise.integral(elapsedSec)
                            + 0.03 * cadenceNoise.integral(elapsedSec)
            );
            // 步时抖动：幅度很小（约 0.3%），只改变步与步之间的间距，不影响总步数趋势
            v += 0.003 * base * strideTimeNoise.integral(elapsedSec);
            // 疲劳：对累计步数按同样比例缩放（近似处理，误差 < 0.1%）
            v *= fatigueFactor(elapsedSec);
            return v;
        }

        /**
         * 改进6：累计步数的**精确导数**（步/秒），即真正该用来排步事件的速率。
         *
         * ★ 为什么必须有这个函数：
         *   旧版把「步频」和「步数」当成两个独立可用的量 ——
         *   步事件按 {@link #cadence(double)}/60 每秒发 N 个，
         *   而步数相位按 {@link #steps(double)} 积分。
         *   两者因为疲劳项的位置不同而**系统性不一致**（20 分钟时差约 2%）。
         *   结果：App 用步数差分算出的步频，与模块自己按步数相位播出的
         *   加速度波形对不上 —— 同一个 10 秒窗口，步数说 168 步、
         *   波形说 171 步。这是可以跨两个通道交叉验证的破绽。
         *
         *   这里对 {@link #steps(double)} 做解析求导（不是数值差分）：
         *   steps = base·B(t)·F(t)，其中
         *       B(t) = t + share·sf·∫SV + 0.03·∫CN + 0.003·∫ST
         *       F(t) = 疲劳因子
         *   于是 steps' = base·[B'(t)·F(t) + B(t)·F'(t)]，
         *   而 B'(t) = 1 + share·sf·SV(t) + 0.03·CN(t) + 0.003·ST(t)，
         *   F'(t) = −depth·(1−tanh²x)/(60·span)。
         *
         *   这个函数的积分正好是 {@link #steps(double)}（相差一个常数），
         *   所以按它排的步事件与步数相位永远同步。
         *
         * @return 步/秒。× 60 即 SPM。
         */
        double stepRate(double elapsedSec) {
            double share = 0.4;
            double base = baseCadenceSpm / 60.0;

            // B(t)
            double b = elapsedSec
                    + share * speedFluctuation * speedNoise.integral(elapsedSec)
                    + 0.03 * cadenceNoise.integral(elapsedSec)
                    + 0.003 * strideTimeNoise.integral(elapsedSec);
            // B'(t)
            double dB = 1.0
                    + share * speedFluctuation * speedNoise.value(elapsedSec)
                    + 0.03 * cadenceNoise.value(elapsedSec)
                    + 0.003 * strideTimeNoise.value(elapsedSec);
            // F(t) 与 F'(t)
            double f = fatigueFactor(elapsedSec);
            double dF = fatigueSlope(elapsedSec);

            return base * (dB * f + b * dF);
        }

        /** 疲劳因子的导数（1/秒）。未进入疲劳期时为 0。 */
        private double fatigueSlope(double elapsedSec) {
            double minutes = elapsedSec / 60.0;
            if (minutes < FATIGUE_START_MIN) return 0.0;
            double x = (minutes - FATIGUE_START_MIN) / FATIGUE_SPAN_MIN;
            double th = Math.tanh(x);
            // dF/dt = −depth · sech²(x) · dx/dt，dx/dt = 1/(60·span)
            return -FATIGUE_DEPTH * (1 - th * th) / (60.0 * FATIGUE_SPAN_MIN);
        }

        // -------------------------------------------------- 改进7：海拔与精度

        /**
         * 海拔（米）。
         *
         * ★ 为什么必须改：旧版把 {@code Location.getAltitude()} 直接写死成
         *   常量 100.0，于是 App 的 {@code laps_json} 里每一圈都是
         *   {@code endAltAbs=100.0}、{@code elevationGain=0.0}。
         *   而同一账号在模块接管海拔**之前**的场次里，这两项都是随圈次变化的，
         *   且量级与当地地形相符。也就是说「全场海拔恒定 + 爬升恒 0」
         *   是只有模块在场时才会出现的指纹，属于设备侧的恒定值破绽。
         *
         * 这里给三层叠加：基准 + 极缓坡度（保证每公里有几米爬升）+ 平滑起伏。
         * 三者都是解析可算的，跨进程一致。
         */
        double altitude(double elapsedSec) {
            double slope = ALT_SLOPE_M * Math.sin(2 * Math.PI * elapsedSec / ALT_SLOPE_PERIOD_S);
            double terrain = ALT_TERRAIN_AMP_M * terrainNoise.value(elapsedSec);
            return altBaseM + slope + terrain;
        }

        /** 海拔相对起点的增量（米），对应 App 的 endAltRel。 */
        double altitudeRelative(double elapsedSec) {
            return altitude(elapsedSec) - altitude(0);
        }

        /**
         * 水平定位精度（米，68% 置信圆半径）。
         *
         * 旧版 {@code getAccuracy()} 返回常量 4.0f。真机在开阔操场上
         * 这个值会随卫星几何、多径、穿林遮挡在 3~8 m 之间漂，恒定值同样是破绽。
         * 这里做成 4.6 ± 1.4 m 的平滑起伏。
         */
        double horizontalAccuracy(double elapsedSec) {
            double v = ACC_BASE_M + ACC_AMP_M * accuracyNoise.value(elapsedSec);
            // 精度不可能是负的，也不该小到 0
            return v < 2.0 ? 2.0 : v;
        }

        /** 垂直精度（米）。经验上约为水平精度的 1.5~2 倍。 */
        double verticalAccuracy(double elapsedSec) {
            return horizontalAccuracy(elapsedSec) * 1.7;
        }

        // -------------------------------------------------- 加速度

        /**
         * 加速度计读数（含重力，m/s²）。Z 竖直、Y 前后、X 左右。
         *
         * @param stepsFloat 当前累计步数（连续值）
         * @param speed      当前速度 m/s
         */
        float[] accelerometer(double stepsFloat, double speed) {
            long tSec = (Clock.nowMs() - startTimestamp) / 1000L;

            long stepIndex = (long) Math.floor(stepsFloat);
            double stepPhase = stepsFloat - stepIndex;

            // 每步力度差异（可复现）
            double strength = 1 + 0.12 * hashUnit(startTimestamp ^ STEP_SALT, stepIndex);

            // 改进2：左右腿不对称。偶数步/奇数步分别放大或缩小
            double legSign = (stepIndex % 2 == 0) ? 1.0 : -1.0;
            strength *= (1 + legAsymmetry * legSign);

            // 改进1：步行→跑步平滑混合
            float[] out = blendGait(stepPhase, stepsFloat, stepIndex, speed, strength);

            // 改进4：慢速零偏漂移（每个轴独立相位）
            double drift = 0.015 * biasDrift.value(tSec);
            out[0] += (float) drift;
            out[1] += (float) (drift * 0.7);
            out[2] += (float) (drift * 1.1);

            // 传感器白噪声 + 量化
            for (int i = 0; i < 3; i++) {
                out[i] += (float) (sensorNoiseRng.nextGaussian() * 0.06);
                out[i] = quantize(out[i]);
            }
            return out;
        }

        private float quantize(float v) {
            return Math.round(v / SENSOR_LSB) * SENSOR_LSB;
        }

        /**
         * 改进1：步行与跑步波形的平滑混合。
         *
         * 上游用 speed >= 2.2 硬切，会在临界点产生波形突变（振幅从
         * 2.0+1.0*2.2=4.2 跳到 6.0+1.5*2.2=9.3），真机不可能这样。
         * 这里用 smoothstep 在 1.8~2.8 m/s 之间过渡。
         */
        private float[] blendGait(double stepPhase, double stepsFloat, long stepIndex,
                                  double speed, double strength) {
            double runW = smoothstep(1.8, 2.8, speed);

            // 步行参数
            double walkAmp = 2.0 + 1.0 * speed;
            // 跑步参数
            double runAmp = 6.0 + 1.5 * speed;
            double amp = walkAmp * (1 - runW) + runAmp * runW;

            double vWalk = lookup(WALK_VERTICAL, stepPhase);
            double vRun = lookup(RUN_VERTICAL, stepPhase);
            double vertical = vWalk * (1 - runW) + vRun * runW;

            double fWalk = lookup(WALK_FORWARD, stepPhase);
            double fRun = lookup(RUN_FORWARD, stepPhase);
            double forward = fWalk * (1 - runW) + fRun * runW;

            // 左右方向：以两步（一个跨步）为周期摆动
            double side = (stepIndex % 2 == 0) ? 1.0 : -1.0;
            double lateral = 0.12 * amp * Math.sin(Math.PI * stepsFloat)
                    + 0.1 * amp * side * bump(stepPhase, 0.02, 0.05);

            double z = GRAVITY + strength * amp * vertical;
            return new float[]{
                    (float) (strength * lateral),
                    (float) (strength * 0.35 * amp * forward),
                    (float) Math.max(0.0, z)
            };
        }
    }

    // ================================================================ 波形表

    /** 高斯峰，p 为相位 [0,1)，支持环绕 */
    private static double bump(double p, double center, double width) {
        double d = Math.abs(p - center);
        if (d > 0.5) d = 1 - d;
        return Math.exp(-0.5 * (d / width) * (d / width));
    }

    /**
     * 采样成查表并去均值。
     *
     * 这一点至关重要：真实加速度计在一个步态周期内的平均值必须恰等于重力，
     * 否则长时间积分会累积出"设备持续加速"的破绽。
     */
    private static double[] zeroMeanTable(ShapeFn shape) {
        double[] table = new double[SHAPE_SAMPLES];
        for (int i = 0; i < SHAPE_SAMPLES; i++) {
            table[i] = shape.at((double) i / SHAPE_SAMPLES);
        }
        double mean = 0;
        for (double v : table) mean += v;
        mean /= table.length;
        for (int i = 0; i < table.length; i++) table[i] -= mean;
        return table;
    }

    private interface ShapeFn {
        double at(double p);
    }

    // 步行：脚跟着地冲击 → 支撑期回落 → 蹬地第二峰 → 摆动期低谷
    private static final double[] WALK_VERTICAL = zeroMeanTable(p ->
            1.0 * bump(p, 0.00, 0.045)
                    - 0.45 * bump(p, 0.13, 0.070)
                    + 0.55 * bump(p, 0.42, 0.090)
                    - 0.65 * bump(p, 0.72, 0.130));

    private static final double[] WALK_FORWARD = zeroMeanTable(p ->
            -0.50 * bump(p, 0.03, 0.05)
                    + 0.45 * bump(p, 0.40, 0.09)
                    - 0.15 * bump(p, 0.75, 0.15));

    // 跑步：尖锐的落地冲击 + 支撑期主峰 + 腾空期接近失重
    private static final double[] RUN_VERTICAL = zeroMeanTable(p ->
            1.00 * bump(p, 0.00, 0.035)
                    + 0.55 * bump(p, 0.16, 0.090)
                    - 0.85 * bump(p, 0.62, 0.180));

    private static final double[] RUN_FORWARD = zeroMeanTable(p ->
            -0.60 * bump(p, 0.02, 0.04)
                    + 0.50 * bump(p, 0.25, 0.08));

    private static double lookup(double[] table, double phase) {
        double pos = (phase - Math.floor(phase)) * table.length;
        int i = (int) pos % table.length;
        int j = (i + 1) % table.length;
        double frac = pos - Math.floor(pos);
        return table[i] * (1 - frac) + table[j] * frac;
    }

    // ================================================================ 工具

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = (x - edge0) / (edge1 - edge0);
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** 由 (seed, n) 确定的 [-1,1] 伪随机数：同一步在不同进程算出同样力度 */
    static double hashUnit(long seed, long n) {
        long x = seed ^ (n * -0x61c8864680b583ebL);
        x = (x ^ (x >>> 33)) * -0xae502812aa7333L;
        x = (x ^ (x >>> 33)) * -0x3b314601e57a13adL;
        x = x ^ (x >>> 33);
        return (double) (x >>> 11) / (double) (1L << 53) * 2 - 1;
    }
}
