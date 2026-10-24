package com.qf.runspoof;

import android.os.SystemClock;

/**
 * 全模块唯一的时间轴：单调、连续、且锚定在真实墙钟上。
 *
 * ── 为什么不能直接用 System.currentTimeMillis() 驱动轨迹 ────────────
 * 整个轨迹是「时间的纯函数」：position = f(t)。喂进去的 t 一跳，位置就
 * 瞬移，而瞬移正是成绩作废的直接原因。
 *
 * 一次作废跑步里 App 自己记录的分段边界（20 秒一档）不是单调递增的，
 * 而是在两条相差数百秒的时间线之间来回跳：相邻分段有时前进几十秒，
 * 有时又倒退回另一个时刻。
 *
 * 同期设备墙钟本身是稳的（wall−uptime 差值抖动在毫秒量级，
 * 且 auto_time=0、NTP 全部超时），所以**不是系统时钟被校正**，
 * 而是**单个 Location 自带的 getTime() 被污染**。
 *
 * 决定性证据来自 dumpsys location：
 *
 *   gps provider: last location=Location[gps ... et=+7m5s181ms ...]
 *   identity=com.nemu.nlp
 *
 * nemu（模拟器假 GPS 守护进程）每秒往系统层塞它自己的 Location，
 * 那份 Location 的 **elapsedRealtime 是新鲜的**（+7m5s 与当时 uptime 吻合），
 * 但 getTime() 是它自己算的墙钟。旧版按「Location 自带的 getTime()」反查坐标，
 * 于是每收到一个 nemu 的点，位置就被甩到几分钟前的路线位置上 ——
 * 平均速度算出 16 m/s（60km/h），分段被打异常标记（state:256），
 * 判「分段不达标」。截图里那些穿过操场中部的红线也是这一跳画出来的。
 *
 * ── 本类的不变量 ────────────────────────────────────────────────────
 * 1. 连续：以 elapsedRealtime 为骨架推进，不受任何墙钟步进影响。
 *    单调性由 elapsedRealtime 自身保证——注意这里**不能**再加全局
 *    高水位钳制：App 会稍后重读历史 Location 的 getter 来算
 *    distanceTo()，钳制会把旧 fix 拉到现在的位置上，距离又变回 0。
 *    纯仿射映射保证「同一对象任何时候读出的时间都相同」。
 * 2. 准：初始化时锚定一次真实墙钟，之后按 elapsedRealtime 递推。
 *    App 会拿设备时间跟服务端下发的跑区时段比对（docs/03 G1），
 *    所以绝对时间必须是对的；锚定一次即可，因为设备墙钟本身稳定。
 *
 * 位置派生与 Location.getTime() 的上报共用这一条时间轴，
 * 因此 distanceTo() 依旧自洽（不同投递时刻 → 不同位置）。
 */
final class Clock {

    /** 允许一个 fix 的 elapsedRealtime 领先「当前」多少（毫秒）仍视为合法。 */
    private static final long FUTURE_TOLERANCE_MS = 60_000L;

    private Clock() {
    }

    /** 锚点：初始化那一刻的真实墙钟（毫秒）。 */
    private static long anchorWall = 0L;
    /** 锚点：同一时刻的 elapsedRealtime（毫秒）。 */
    private static long anchorRt = 0L;
    /** 用独立标志判断是否已锚定，不能拿 anchorRt==0 当哨兵（开机 0ms 合法）。 */
    private static boolean anchored = false;

    private static long nowRt() {
        return SystemClock.elapsedRealtime();
    }

    /** 当前时间（毫秒）。连续。 */
    static long nowMs() {
        return wallMsForRt(nowRt());
    }

    /**
     * 把某个 Location 的 elapsedRealtime（毫秒）映射到统一时间轴。
     *
     * 这是修掉瞬移的关键：不再读那个可能被污染的 getTime()，
     * 而是读由系统按 fix 真实投递时刻赋值的 elapsedRealtime。
     *
     * ★ 必须是纯函数：同一个入参，无论何时调用，永远返回同一个值。
     * 原因见 docs/00 B4 —— App 会稍后重读历史 Location 来算 distanceTo()，
     * 只要两次读出的时间不同，两点就会被算成同一个位置（距离恒为 0），
     * 里程统计直接崩掉。
     *
     * 所以这里的兜底**不能用「当前时刻」**：那会让同一对象的返回值随时间漂移。
     * 早期版本写过 `rtMs > nowRt` 就钳到 nowRt，看着合理，实则是上面那个坑
     * 的复发（同一 Location 在不同时刻读到不同时间）。现在改成返回固定的
     * anchorWall，仍然可重入。
     *
     * 合法区间内走纯仿射：anchorWall + (rtMs - anchorRt)。
     * 兜底只覆盖物理上不可能的值：未赋值（<=0）、或离谱到不可能是本次开机的 fix。
     * 刻意不设「过去」的下限——一次跑 20 分钟，App 完全可能重读 40 分钟前的
     * fix，那是合法历史，不是垃圾。
     */
    static synchronized long wallMsForRt(long rtMs) {
        long nowRt = nowRt();
        if (!anchored) {
            anchored = true;
            anchorRt = nowRt;
            anchorWall = System.currentTimeMillis();
        }
        // 垃圾值：未赋值，或比「现在」还超前一大截（跨 boot / 未初始化的脏数据）。
        // 阈值取 60s：真机上一个 fix 的 elapsedRealtime 不可能显著领先于当前时刻。
        if (rtMs <= 0L || rtMs > nowRt + FUTURE_TOLERANCE_MS) {
            return anchorWall;      // 固定值，保证可重入
        }
        return anchorWall + (rtMs - anchorRt);
    }
}
