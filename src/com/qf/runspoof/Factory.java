package com.qf.runspoof;

import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射工厂：不依赖任何 Xposed API，纯 Java 反射。
 * 这样 RouteEngine / Factory 可以脱离框架版本独立复用和测试。
 */
final class Factory {

    private Factory() {
    }

    // ---------------------------------------------------------------- 反射缓存

    private static Method mSetMock;
    private static Field fMockFlag;
    private static boolean mockResolved;

    private static void resolveLocationMock() {
        if (mockResolved) return;
        mockResolved = true;
        try {
            mSetMock = Location.class.getDeclaredMethod("setIsFromMockProvider", boolean.class);
            mSetMock.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            fMockFlag = Location.class.getDeclaredField("mIsFromMockProvider");
            fMockFlag.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- Location

    /**
     * 缓存 Location 的私有坐标字段。
     *
     * ★ 为什么必须直接写字段：
     * Location.distanceTo() / Location.distanceBetween() 是 native 实现，
     * 直接读 mLatitude/mLongitude 字段，不走 getLatitude()/getLongitude()。
     * 模块虽然 hook 了 getter（按时间戳返回路线坐标），但 native 侧看到的
     * 仍是字段里的旧值。只要字段是 0，任意两点距离恒为 0，
     * app 若用 distanceTo 累加里程就永远是 0 米。
     */
    private static Field fLat, fLon, fAlt, fAcc, fSpeed, fBearing;
    private static boolean locFieldsResolved;

    private static void resolveLocFields() {
        if (locFieldsResolved) return;
        locFieldsResolved = true;
        try {
            Class<?> c = Location.class;
            fLat = declared(c, "mLatitude");
            fLon = declared(c, "mLongitude");
            fAlt = declared(c, "mAltitude");
            fAcc = declared(c, "mAccuracy");
            fSpeed = declared(c, "mSpeed");
            fBearing = declared(c, "mBearing");
        } catch (Throwable ignored) {
        }
    }

    private static Field declared(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    static Location buildLocation(String provider) {
        // 时间轴与位置必须同源：先取 elapsedRealtime，再由它推出墙钟时间和坐标。
        // 这样 Location 的 getTime() / elapsedRealtimeNanos / 坐标三者自洽，
        // App 无论读哪个字段，落回 RouteEngine 都是同一个点。
        long rtMs = SystemClock.elapsedRealtime();
        long now = Clock.wallMsForRt(rtMs);
        double[] pos = RouteEngine.positionAt(now);
        double speed = RouteEngine.currentSpeed(now);

        Location loc = new Location(provider == null ? "gps" : provider);
        // ★ 海拔、精度都必须随时间变化，不能是常量。
        //   旧版写死 alt=100.0/acc=4.0，于是 App 的 laps_json 里
        //   endAltAbs 全场恒为 100.0、elevationGain 全场恒为 0.0 ——
        //   而模块接管之前的场次这两项都是变化的，这个"恒定"本身就是指纹。
        //   这两个量都由 GaitEngine 给出解析可算的平滑曲线，跨进程一致。
        double alt = RouteEngine.currentAltitude(now);
        float acc = (float) RouteEngine.currentHorizontalAccuracy(now);
        float bear = RouteEngine.bearingAt(now);

        loc.setLatitude(pos[0]);
        loc.setLongitude(pos[1]);
        loc.setAltitude(alt);
        loc.setAccuracy(acc);
        loc.setSpeed((float) speed);
        loc.setBearing(bear);
        loc.setTime(now);
        if (Build.VERSION.SDK_INT >= 17) {
            loc.setElapsedRealtimeNanos(rtMs * 1_000_000L);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                loc.setVerticalAccuracyMeters((float) RouteEngine.currentVerticalAccuracy(now));
                loc.setSpeedAccuracyMetersPerSecond(0.15f);
                loc.setBearingAccuracyDegrees(2.0f);
            } catch (Throwable ignored) {
            }
        }
        // 让 native 的 distanceTo 也能看到正确坐标
        writeFields(loc, pos[0], pos[1], alt, acc, (float) speed, bear);
        scrubMockFlag(loc);

        Bundle extras = new Bundle();
        extras.putInt("satellites", 18);
        extras.putFloat("maxCn0", 46f);
        extras.putFloat("meanCn0", 38f);
        loc.setExtras(extras);
        return loc;
    }

    /** 直接写 Location 私有字段，供 native distanceTo 使用。 */
    private static void writeFields(Location loc, double lat, double lon,
                                    double alt, float acc, float speed, float bear) {
        resolveLocFields();
        try {
            if (fLat != null) fLat.setDouble(loc, lat);
            if (fLon != null) fLon.setDouble(loc, lon);
            if (fAlt != null) fAlt.setDouble(loc, alt);
            if (fAcc != null) fAcc.setFloat(loc, acc);
            if (fSpeed != null) fSpeed.setFloat(loc, speed);
            if (fBearing != null) fBearing.setFloat(loc, bear);
        } catch (Throwable ignored) {
        }
    }

    static void scrubMockFlag(Location loc) {
        if (loc == null) return;
        resolveLocationMock();
        try {
            if (mSetMock != null) mSetMock.invoke(loc, false);
        } catch (Throwable ignored) {
        }
        try {
            if (fMockFlag != null) fMockFlag.setBoolean(loc, false);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- GnssStatus

    private static Constructor<?> gnssBuilderCtor;
    private static Method mAddSatellite;
    private static Method mBuild;
    private static boolean gnssResolved;
    private static boolean gnssUsable;

    static Object buildGnssStatus(ClassLoader cl, int count) {
        if (!gnssResolved) {
            gnssResolved = true;
            try {
                Class<?> builderCls = Class.forName("android.location.GnssStatus$Builder", false, cl);
                gnssBuilderCtor = builderCls.getDeclaredConstructor();
                gnssBuilderCtor.setAccessible(true);
                for (Method m : builderCls.getDeclaredMethods()) {
                    if ("addSatellite".equals(m.getName())) {
                        mAddSatellite = m;
                        mAddSatellite.setAccessible(true);
                    } else if ("build".equals(m.getName())) {
                        mBuild = m;
                        mBuild.setAccessible(true);
                    }
                }
                gnssUsable = (gnssBuilderCtor != null && mAddSatellite != null && mBuild != null);
            } catch (Throwable t) {
                gnssUsable = false;
            }
        }
        if (!gnssUsable) return null;

        try {
            Object builder = gnssBuilderCtor.newInstance();
            int n = Math.max(4, Math.min(count, 32));
            for (int i = 0; i < n; i++) {
                int type = CONSTELLATION[i % CONSTELLATION.length];
                int svid = SVID[i % SVID.length];
                float cn0 = 32f + (i * 1.3f) % 11f;
                float elev = 20f + (i * 7.5f) % 60f;
                float azim = (i * 33.7f) % 360f;
                boolean usedInFix = elev > 25f;
                // Android 11+ 的 addSatellite 有 8 参和 12 参两种重载，按参数个数适配
                int pc = mAddSatellite.getParameterCount();
                if (pc >= 12) {
                    mAddSatellite.invoke(builder, type, svid, cn0, elev, azim,
                            true, true, usedInFix, false, 0f, false, 0f);
                } else {
                    mAddSatellite.invoke(builder, type, svid, cn0, elev, azim,
                            true, true, usedInFix);
                }
            }
            return mBuild.invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final int[] CONSTELLATION = {1, 5, 3, 6};  // GPS 北斗 GLONASS Galileo
    private static final int[] SVID = {5, 12, 66, 3};

    // ---------------------------------------------------------------- ScanResult

    private static Constructor<?> scanCtor;
    private static boolean scanResolved;

    static Object buildScanResult(ClassLoader cl, String ssid, String bssid,
                                  int level, int freq) {
        if (!scanResolved) {
            scanResolved = true;
            try {
                Class<?> c = Class.forName("android.net.wifi.ScanResult", false, cl);
                scanCtor = c.getDeclaredConstructor();
                scanCtor.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        if (scanCtor == null) return null;
        try {
            Object r = scanCtor.newInstance();
            setField(r, "SSID", ssid);
            setField(r, "BSSID", bssid);
            setIntField(r, "level", level);
            setIntField(r, "frequency", freq);
            setField(r, "capabilities", "[WPA2-PSK-CCMP][ESS]");
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- CellInfo

    static Object buildCellInfo(ClassLoader cl, int ci, int pci, int tac, int earfcn) {
        try {
            Class<?> identCls = Class.forName("android.telephony.CellIdentityLte", false, cl);
            Class<?> sigCls = Class.forName("android.telephony.CellSignalStrengthLte", false, cl);
            Class<?> infoCls = Class.forName("android.telephony.CellInfoLte", false, cl);

            Constructor<?> identCtor = identCls.getDeclaredConstructor();
            identCtor.setAccessible(true);
            Object ident = identCtor.newInstance();
            setIntField(ident, "mCi", ci);
            setIntField(ident, "mPci", pci);
            setIntField(ident, "mTac", tac);
            setIntField(ident, "mEarfcn", earfcn);

            Constructor<?> sigCtor = sigCls.getDeclaredConstructor();
            sigCtor.setAccessible(true);
            Object sig = sigCtor.newInstance();
            setIntField(sig, "mRssi", -65);
            setIntField(sig, "mRsrp", -95);
            setIntField(sig, "mRsrq", -10);

            Constructor<?> infoCtor = infoCls.getDeclaredConstructor();
            infoCtor.setAccessible(true);
            Object info = infoCtor.newInstance();
            setField(info, "mCellIdentityLte", ident);
            setField(info, "mCellSignalStrengthLte", sig);
            return info;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 反射辅助

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = findField(obj.getClass(), name);
            if (f != null) f.set(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setIntField(Object obj, String name, int value) {
        try {
            Field f = findField(obj.getClass(), name);
            if (f != null) f.setInt(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
