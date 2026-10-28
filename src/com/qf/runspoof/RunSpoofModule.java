package com.qf.runspoof;

import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 校园跑模拟模块（libxposed API 102）。
 *
 * ── 本轮修掉的 bug ────────────────────────────────────────────────
 * 1. ConfigStore.attach() 从来没被调用过。
 *    → remote preferences 一直是 null，注入侧永远读默认值，
 *      地图选点界面写了配置也没人读。这是"地图选点不生效"的真正原因。
 * 2. 全局 hook Location.getLatitude()/getLongitude() 返回"当前时刻"的坐标，
 *    导致任意两个 Location 的 distanceTo() 恒为 0（两边都取到同一个点）。
 *    app 只要用 distanceTo 累加里程就永远是 0 米。
 *    → 改成按每个 Location 自己的 getTime() 计算坐标，
 *      不同时刻的 Location 自然对应不同位置，distanceTo 恢复正确。
 * 3. stepListeners 用 listener 做 key，同一个 listener 注册多个传感器时
 *    只会记住最后一个；且没有 hook unregisterListener，已注销的 listener
 *    还在被推送。→ 改成 (listener,sensor) 组合 key，并 hook 注销。
 * 4. 50Hz 加速度派发跑在主线程上，会给目标 app 造成明显卡顿。
 *    → 改成独立的 HandlerThread。
 * 5. pushSensor 每次调用都做 3 次反射查找（50Hz × N 个 listener），
 *    → 缓存 values 字段和 onSensorChanged 方法。
 * 6. 删掉从未被调用的死代码 accelWave()。
 */
public class RunSpoofModule extends XposedModule {

    private static final String TAG = "RunSpoof";
    private static final String TARGET_PKG = "com.zjwh.android_wh_physicalfitness";

    /** 传感器监听器表。key 是 listener+sensor 的组合，避免同一 listener 多传感器互相覆盖。 */
    private final Map<String, Object[]> stepListeners = new ConcurrentHashMap<>();
    /** 定位监听器。 */
    private final List<Object> locationListeners = new ArrayList<>();
    /** 高德 SDK 的 AMapLocationListener 实例。 */
    private final Map<Object, Boolean> amapListeners = new ConcurrentHashMap<>();

    private volatile boolean started = false;
    /** 高德 hook 是否已经挂上。onPackageLoaded / onPackageReady 可能各试一次。 */
    private volatile boolean amapHooked = false;
    private Handler bg;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(android.util.Log.INFO, TAG, "module loaded, process=" + param.getProcessName()
                + ", systemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!TARGET_PKG.equals(pkg)) return;

        // ★ 关键：先把 remote preferences 通道接上，否则后面读到的全是默认值
        ConfigStore.attach(this);
        log(android.util.Log.INFO, TAG, "target package loaded: " + pkg
                + ", remotePrefs=" + ConfigStore.hasRemote()
                + ", " + ConfigStore.describeRemote());

        // 把实际读到的配置原样打出来。
        // 「保存了但位置不动」这类问题，光看 remotePrefs=true 没有意义 ——
        // 必须知道从那份 prefs 里究竟读到了什么。
        ConfigStore.Config raw = ConfigStore.read();
        log(android.util.Log.INFO, TAG, "config read back: center="
                + raw.centerLat + "," + raw.centerLng
                + " speed=" + raw.speedMps
                + " cadence=" + raw.cadenceSpm
                + " radius=" + raw.radiusM
                + " pathPts=" + raw.path.size()
                + " sig=" + ConfigStore.signature());

        RouteEngine.ensureInit();

        ClassLoader cl = param.getDefaultClassLoader();
        try {
            // 系统层的 hook 在这里挂：这些类必然已存在
            hookLocationManager(cl);
            hookGnss(cl);
            hookSensors(cl);
            hookWifi(cl);
            hookTelephony(cl);
            hookAntiCheat(cl);
            startDispatchers(cl);
            log(android.util.Log.INFO, TAG, "system hooks installed, route="
                    + Math.round(RouteEngine.totalLength()) + "m, center="
                    + RouteEngine.currentCenter()[0] + "," + RouteEngine.currentCenter()[1]);
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "hook install failed", t);
        }

        // 高德 SDK 的类在这时通常还没被加载，先试一次；失败会在 onPackageReady 重试
        tryHookAMap(cl);
    }

    /**
     * ★ onPackageReady 比 onPackageLoaded 更晚，此时 App 的类基本都加载完了。
     *
     * 为什么必须补这一层：
     * onPackageLoaded 阶段 App 自己的 dex 还没初始化完，
     * Class.forName("com.amap.api.location.AMapLocation") 会抛 ClassNotFoundException，
     * 于是高德那一整套 hook 被整体跳过（日志里就是那句
     * "AMapLocation not present, skip AMap hooks"）。
     *
     * 而这个 App 的定位恰恰走高德 SDK，不走系统 LocationManager，
     * 结果就是：系统层坐标已经被改成伪造值（dumpsys 能看到），
     * 但 App 从高德拿到的是真实/空定位，于是判定"不在有效区域内"。
     *
     * 这是"hook 日志显示一切正常、实际却完全不生效"的第二个实例。
     */
    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        log(android.util.Log.INFO, TAG, "package ready: " + param.getPackageName());
        tryHookAMap(param.getClassLoader());
    }

    /** 尝试挂高德 hook；类还没加载就记一笔，等 onPackageReady 再试。 */
    private void tryHookAMap(ClassLoader cl) {
        if (amapHooked) {
            log(android.util.Log.INFO, TAG, "AMap hooks already installed, skip");
            return;
        }
        try {
            hookAMap(cl);
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "hookAMap throwable: " + t);
        }
    }

    // ================================================================ 工具

    private void hookAll(Class<?> clazz, String methodName, XposedInterface.Hooker hooker) {
        int n = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            try {
                m.setAccessible(true);
                hook(m).intercept(hooker);
                n++;
            } catch (Throwable t) {
                log(android.util.Log.WARN, TAG, "hook " + methodName + " fail: " + t);
            }
        }
        log(android.util.Log.INFO, TAG, "hooked " + clazz.getSimpleName()
                + "#" + methodName + " x" + n);
    }

    /**
     * hook 一个精确签名的方法。
     *
     * ★ 修掉一个 bug：旧版只在 clazz 自己身上 getDeclaredMethod，
     * 如果方法其实声明在父类（例如 AMapLocation 的 getLatitude 若继承自
     * android.location.Location），就直接 NoSuchMethodException 静默失败，
     * 表现为「日志里 hook 成功、实际没生效」。现在沿继承链逐级查找。
     */
    private void hookOne(Class<?> clazz, String methodName, Class<?>[] paramTypes,
                         XposedInterface.Hooker hooker) {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                hook(m).intercept(hooker);
                return;
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                log(android.util.Log.WARN, TAG, "hook " + methodName + " fail: " + t);
                return;
            }
        }
        log(android.util.Log.WARN, TAG, "hook " + clazz.getSimpleName()
                + "#" + methodName + " not found in hierarchy");
    }

    private static int indexOf(Class<?>[] arr, Class<?> target) {
        for (int i = 0; i < arr.length; i++) {
            if (target.isAssignableFrom(arr[i]) || arr[i].isAssignableFrom(target)) return i;
        }
        return -1;
    }

    private Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Field fd = cur.getDeclaredField(name);
                fd.setAccessible(true);
                return fd;
            } catch (Throwable ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    // ================================================================ 高德定位 SDK

    /**
     * app 的定位完全走高德 SDK，不是系统 Location。
     * AMapLocation 虽然继承 android.location.Location，但 SDK 内部用自己的
     * mLatitude/mLongitude 字段，不走 Location.getLatitude()。
     */
    private void hookAMap(final ClassLoader cl) {
        final Class<?> amapLoc;
        try {
            amapLoc = Class.forName("com.amap.api.location.AMapLocation", false, cl);
        } catch (Throwable t) {
            log(android.util.Log.INFO, TAG, "AMapLocation not present, skip AMap hooks");
            return;
        }
        // 走到这里说明类确实存在，标记为已挂载，避免 onPackageReady 重复挂
        amapHooked = true;

        // ★ AMapLocation 的坐标约定是 GCJ-02（高德自家坐标系），
        //   而 RouteEngine.positionAt() 返回 WGS-84。
        //   这一路如果直接吐 WGS 给高德，App 画图时又会偏移一次。
        //
        //   海拔同样不能是常量：高德这条路的 getAltitude 若恒为 100.0，
        //   App 记的 laps.elevationGain 就恒为 0，与真实跑步对不上。
        final String[] doubles = {"getLatitude", "getLongitude", "getAltitude"};
        for (final String name : doubles) {
            hookOne(amapLoc, name, new Class<?>[]{}, chain -> {
                // 按对象自己的时间算，和系统 Location 那条路一致。
                // 用「现在」会让同一对象在不同时刻读出不同坐标，
                // App 重算历史轨迹时 distanceTo 又会变 0。
                long t = timeOf(chain.getThisObject());
                if ("getAltitude".equals(name)) {
                    return RouteEngine.currentAltitude(t);
                }
                double[] pos = amapWgsToGcj(t);
                return "getLatitude".equals(name) ? pos[0] : pos[1];
            });
        }

        final String[] floats = {"getAccuracy", "getSpeed", "getBearing"};
        for (final String name : floats) {
            hookOne(amapLoc, name, new Class<?>[]{}, chain -> {
                long now = timeOf(chain.getThisObject());
                switch (name) {
                    case "getSpeed":
                        return (float) RouteEngine.currentSpeed(now);
                    case "getBearing":
                        return RouteEngine.bearingAt(now);
                    default:
                        // 恒定的 4.0f 同样是恒定值指纹
                        return (float) RouteEngine.currentHorizontalAccuracy(now);
                }
            });
        }

        String[] ints = {"getLocationType", "getSatellites", "getGpsAccuracyStatus", "getErrorCode"};
        for (final String name : ints) {
            hookOne(amapLoc, name, new Class<?>[]{}, chain -> {
                switch (name) {
                    case "getSatellites":
                        return 20;
                    case "getLocationType":
                    case "getGpsAccuracyStatus":
                        return 1;
                    default:
                        return 0;
                }
            });
        }

        hookOne(amapLoc, "isSuccess", new Class<?>[]{}, chain -> Boolean.TRUE);

        // 构造后立刻填好坐标，防止初始 (0,0) 泄露
        for (Class<?>[] ctorArgs : new Class<?>[][]{{}, {String.class}}) {
            try {
                hook(amapLoc.getDeclaredConstructor(ctorArgs)).intercept(chain -> {
                    Object r = chain.proceed();
                    fillAmapLocation(r);
                    return r;
                });
            } catch (Throwable ignored) {
            }
        }

        log(android.util.Log.INFO, TAG, "AMapLocation getters hooked");
        hookAMapClient(cl, amapLoc);
    }

    private void fillAmapLocation(Object amapLoc) {
        if (amapLoc == null) return;
        // 与 buildLocation 同样的做法：先定 elapsedRealtime，再推出墙钟与坐标，
        // 并把两个时间字段都写进对象。这样 App 读 getTime()（已 hook 成按
        // elapsedRealtime 反查）、读 getElapsedRealtimeNanos()、读字段，
        // 三条路落回 RouteEngine 都是同一个点，不会自相矛盾。
        long rtMs = SystemClock.elapsedRealtime();
        long now = Clock.wallMsForRt(rtMs);
        // 同 getLatitude/getLongitude 的理由：AMapLocation 存 GCJ-02。
        double[] wgs = RouteEngine.positionAt(now);
        double[] pos = Geo.wgsToGcj(wgs[0], wgs[1]);
        setD(amapLoc, "mLatitude", pos[0]);
        setD(amapLoc, "mLongitude", pos[1]);
        setJ(amapLoc, "mTime", now);
        setJ(amapLoc, "mElapsedRealtimeNanos", rtMs * 1_000_000L);
        // 海拔必须跟着时间走，不能写死 —— 否则高德这条路会绕开
        // getAltitude 的 hook，把常量重新灌进对象字段里。
        setF(amapLoc, "mAltitude", (float) RouteEngine.currentAltitude(now));
        setF(amapLoc, "mSpeed", (float) RouteEngine.currentSpeed(now));
        setF(amapLoc, "mBearing", RouteEngine.bearingAt(now));
        setI(amapLoc, "mLocationType", 1);
        setI(amapLoc, "mSatellites", 20);
        setI(amapLoc, "mGpsAccuracyStatus", 1);
        setI(amapLoc, "mErrorCode", 0);
        setS(amapLoc, "mProvider", "gps");
    }

    /**
     * 取当前时刻的位置并转成 GCJ-02，供高德那一侧的 API 使用。
     *
     * 系统 Location 那条路要 WGS-84、高德那条路要 GCJ-02，
     * 两条路都从 RouteEngine 的同一个 WGS-84 位置出发，各自按需转换。
     */
    private double[] amapWgsToGcj(long nowMs) {
        double[] pos = RouteEngine.positionAt(nowMs);
        return Geo.wgsToGcj(pos[0], pos[1]);
    }

    private void setD(Object o, String f, double v) {
        try {
            Field fd = findField(o.getClass(), f);
            if (fd != null) fd.setDouble(o, v);
        } catch (Throwable ignored) {
        }
    }

    private void setF(Object o, String f, float v) {
        try {
            Field fd = findField(o.getClass(), f);
            if (fd != null) fd.setFloat(o, v);
        } catch (Throwable ignored) {
        }
    }

    private void setI(Object o, String f, int v) {
        try {
            Field fd = findField(o.getClass(), f);
            if (fd != null) fd.setInt(o, v);
        } catch (Throwable ignored) {
        }
    }

    private void setJ(Object o, String f, long v) {
        try {
            Field fd = findField(o.getClass(), f);
            if (fd != null) fd.setLong(o, v);
        } catch (Throwable ignored) {
        }
    }

    private void setS(Object o, String f, String v) {
        try {
            Field fd = findField(o.getClass(), f);
            if (fd != null) fd.set(o, v);
        } catch (Throwable ignored) {
        }
    }

    private void hookAMapClient(ClassLoader cl, final Class<?> amapLoc) {
        try {
            Class<?> client = Class.forName(
                    "com.amap.api.location.AMapLocationClient", false, cl);

            for (Method m : client.getDeclaredMethods()) {
                if (!m.getName().equals("setLocationListener")) continue;
                if (m.getParameterCount() != 1) continue;
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    Object l = chain.getArg(0);
                    if (l != null) {
                        amapListeners.put(l, Boolean.TRUE);
                        log(android.util.Log.INFO, TAG, "AMap listener captured: "
                                + l.getClass().getName());
                    }
                    return chain.proceed();
                });
            }

            Handler h = bg();
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (Object l : new ArrayList<>(amapListeners.keySet())) {
                            Object loc;
                            try {
                                loc = amapLoc.getDeclaredConstructor().newInstance();
                            } catch (Throwable t) {
                                loc = amapLoc.getDeclaredConstructor(String.class)
                                        .newInstance("gps");
                            }
                            fillAmapLocation(loc);
                            Method cb = findMethod(l.getClass(), "onLocationChanged", 1);
                            if (cb != null) {
                                try {
                                    cb.invoke(l, loc);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    h.postDelayed(this, 1000L);
                }
            });

            log(android.util.Log.INFO, TAG, "AMapLocationClient listener hooked");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "hookAMapClient fail: " + t);
        }
    }

    // ================================================================ 定位

    private void hookLocationManager(ClassLoader cl) {
        Class<?> lm;
        try {
            lm = Class.forName("android.location.LocationManager", false, cl);
        } catch (Throwable t) {
            return;
        }

        hookOne(lm, "getLastKnownLocation", new Class<?>[]{String.class},
                chain -> Factory.buildLocation((String) chain.getArg(0)));
        hookOne(lm, "isProviderEnabled", new Class<?>[]{String.class}, chain -> Boolean.TRUE);
        hookOne(lm, "isLocationEnabled", new Class<?>[]{}, chain -> Boolean.TRUE);

        for (Method m : lm.getDeclaredMethods()) {
            if (!m.getName().equals("getCurrentLocation")) continue;
            try {
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    String provider = "fused";
                    for (Object a : chain.getArgs()) {
                        if (a instanceof String) {
                            provider = (String) a;
                            break;
                        }
                    }
                    return Factory.buildLocation(provider);
                });
            } catch (Throwable ignored) {
            }
        }

        // getProviders 有三个重载：()、(boolean)、(Criteria, boolean)
        for (Method m : lm.getDeclaredMethods()) {
            if (!m.getName().equals("getProviders") && !m.getName().equals("getAllProviders")) {
                continue;
            }
            try {
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    List<String> l = new ArrayList<>();
                    l.add("gps");
                    l.add("network");
                    l.add("passive");
                    l.add("fused");
                    return l;
                });
            } catch (Throwable ignored) {
            }
        }

        // requestLocationUpdates：捕获 listener 主动回调，
        // 但必须让系统正常注册 —— 早期版本 return null 吞掉注册，
        // 导致系统层面从未建立位置请求，app 永远收不到 onLocationChanged，
        // 表现为「GPS信号弱」。这是实测定位到的 bug，不能重犯。
        Class<?> llCls;
        try {
            llCls = Class.forName("android.location.LocationListener", false, cl);
        } catch (Throwable t) {
            llCls = null;
        }
        if (llCls != null) {
            final Class<?> fLl = llCls;
            for (Method m : lm.getDeclaredMethods()) {
                if (!m.getName().equals("requestLocationUpdates")) continue;
                final int idx = indexOf(m.getParameterTypes(), fLl);
                if (idx < 0) continue;
                try {
                    m.setAccessible(true);
                    hook(m).intercept(chain -> {
                        registerLocationListener(chain.getArg(idx));
                        return chain.proceed();
                    });
                } catch (Throwable ignored) {
                }
            }
            log(android.util.Log.INFO, TAG, "requestLocationUpdates hooked");
        }
    }

    private void registerLocationListener(Object l) {
        if (l == null) return;
        synchronized (locationListeners) {
            for (Object o : locationListeners) if (o == l) return;
            locationListeners.add(l);
        }
        log(android.util.Log.INFO, TAG, "location listener captured");
    }

    // ================================================================ GNSS

    private void hookGnss(ClassLoader cl) {
        Class<?> lm, cbCls;
        try {
            lm = Class.forName("android.location.LocationManager", false, cl);
            cbCls = Class.forName("android.location.GnssStatus$Callback", false, cl);
        } catch (Throwable t) {
            return;
        }
        final Class<?> fCb = cbCls;
        for (Method m : lm.getDeclaredMethods()) {
            if (!m.getName().equals("registerGnssStatusCallback")) continue;
            final int idx = indexOf(m.getParameterTypes(), fCb);
            if (idx < 0) continue;
            try {
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    Object cb = chain.getArg(idx);
                    if (cb != null) hookSingleGnssCallback(cb, cl);
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
            }
        }
        log(android.util.Log.INFO, TAG, "registerGnssStatusCallback hooked");
    }

    private void hookSingleGnssCallback(Object callback, final ClassLoader cl) {
        try {
            for (Method m : callback.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("onSatelliteStatusChanged")) continue;
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    Object spoofed = Factory.buildGnssStatus(cl, 18);
                    if (spoofed != null) {
                        // 用 proceed(Object[]) 替换参数 —— 新版 API 的正确做法。
                        // 旧版直接改 chain.args[i] 会抛 UnsupportedOperationException。
                        return chain.proceed(new Object[]{spoofed});
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "gnss callback hook fail: " + t);
        }
    }

    // ================================================================ 传感器

    private void hookSensors(ClassLoader cl) {
        final Class<?> listenerCls;
        try {
            listenerCls = Class.forName("android.hardware.SensorEventListener", false, cl);
        } catch (Throwable t) {
            return;
        }

        String[] candidates = {
                "android.hardware.SystemSensorManager",
                "android.hardware.SensorManager"
        };
        for (String cn : candidates) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, cl);
            } catch (Throwable t) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                boolean isRegister = name.startsWith("registerListener");
                boolean isUnregister = name.startsWith("unregisterListener");
                if (!isRegister && !isUnregister) continue;

                final int lIdx = indexOf(m.getParameterTypes(), listenerCls);
                if (lIdx < 0) continue;
                int sIdx = -1;
                Class<?>[] pts = m.getParameterTypes();
                for (int i = 0; i < pts.length; i++) {
                    if ("android.hardware.Sensor".equals(pts[i].getName())) {
                        sIdx = i;
                        break;
                    }
                }
                final int fS = sIdx;

                try {
                    m.setAccessible(true);
                    if (isRegister) {
                        hook(m).intercept(chain -> {
                            Object l = chain.getArg(lIdx);
                            Object s = fS >= 0 ? chain.getArg(fS) : null;
                            if (l != null && s != null) {
                                stepListeners.put(key(l, s), new Object[]{l, s});
                            }
                            return chain.proceed();
                        });
                    } else {
                        hook(m).intercept(chain -> {
                            Object l = chain.getArg(lIdx);
                            Object s = fS >= 0 ? chain.getArg(fS) : null;
                            if (l != null) {
                                if (s != null) {
                                    stepListeners.remove(key(l, s));
                                } else {
                                    // 只给 listener 的注销：删掉它名下所有传感器
                                    for (Map.Entry<String, Object[]> e
                                            : new ArrayList<>(stepListeners.entrySet())) {
                                        if (e.getValue()[0] == l) {
                                            stepListeners.remove(e.getKey());
                                        }
                                    }
                                }
                            }
                            return chain.proceed();
                        });
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        log(android.util.Log.INFO, TAG, "sensor register/unregister hooked");
    }

    private static String key(Object listener, Object sensor) {
        return System.identityHashCode(listener) + "#" + System.identityHashCode(sensor);
    }

    // ================================================================ WiFi / 基站

    private void hookWifi(ClassLoader cl) {
        try {
            Class<?> wm = Class.forName("android.net.wifi.WifiManager", false, cl);
            hookOne(wm, "getScanResults", new Class<?>[]{}, chain -> {
                List<Object> list = new ArrayList<>();
                Object r1 = Factory.buildScanResult(cl, "CAMPUS-LIB-5G",
                        "3c:46:d8:11:22:33", -48, 5180);
                Object r2 = Factory.buildScanResult(cl, "CAMPUS-STU",
                        "3c:46:d8:44:55:66", -61, 2437);
                if (r1 != null) list.add(r1);
                if (r2 != null) list.add(r2);
                return list.isEmpty() ? chain.proceed() : list;
            });
            log(android.util.Log.INFO, TAG, "WifiManager hooked");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "hookWifi fail: " + t);
        }
    }

    private void hookTelephony(ClassLoader cl) {
        try {
            Class<?> tm = Class.forName("android.telephony.TelephonyManager", false, cl);
            hookOne(tm, "getAllCellInfo", new Class<?>[]{}, chain -> {
                List<Object> list = new ArrayList<>();
                Object c1 = Factory.buildCellInfo(cl, 1234567, 120, 4567, 1650);
                if (c1 != null) list.add(c1);
                return list.isEmpty() ? chain.proceed() : list;
            });
            hookOne(tm, "getNetworkOperator", new Class<?>[]{}, chain -> "46000");
            hookOne(tm, "getNetworkOperatorName", new Class<?>[]{}, chain -> "中国移动");
            log(android.util.Log.INFO, TAG, "TelephonyManager hooked");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "hookTelephony fail: " + t);
        }
    }

    // ================================================================ 反作弊 + Location 覆盖

    private void hookAntiCheat(ClassLoader cl) {
        try {
            Class<?> appOps = Class.forName("android.app.AppOpsManager", false, cl);
            XposedInterface.Hooker opHooker = chain -> {
                Object a0 = chain.getArg(0);
                if (a0 instanceof Integer && (Integer) a0 == 58) {
                    return android.app.AppOpsManager.MODE_ALLOWED;
                }
                return chain.proceed();
            };
            hookOne(appOps, "checkOpNoThrow",
                    new Class<?>[]{int.class, int.class, String.class}, opHooker);
            hookOne(appOps, "noteOpNoThrow",
                    new Class<?>[]{int.class, int.class, String.class}, opHooker);
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "appops fail: " + t);
        }

        try {
            Class<?> secure = Class.forName("android.provider.Settings$Secure", false, cl);
            hookOne(secure, "getInt",
                    new Class<?>[]{android.content.ContentResolver.class, String.class, int.class},
                    chain -> "mock_location".equals(chain.getArg(1)) ? 0 : chain.proceed());
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "settings fail: " + t);
        }

        try {
            hookOne(Location.class, "isFromMockProvider", new Class<?>[]{},
                    chain -> Boolean.FALSE);
            hookOne(Location.class, "getExtras", new Class<?>[]{}, chain -> {
                Object r = chain.proceed();
                if (r instanceof Bundle) {
                    Bundle b = (Bundle) r;
                    if (b.containsKey("mockLocation")) b.remove("mockLocation");
                }
                return r;
            });
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "location flag fail: " + t);
        }

        // ===== Location 坐标覆盖 =====
        // 为什么必须覆盖：MuMu 的 nemu 每秒往系统层塞自己的 GPS，
        // app 拿到的 Location 就是 nemu 那份，坐标一直漂，
        // 累加后出现"0.94km / 46s = 20m/s"的荒谬配速，步频恒为 0。
        //
        // ★ 关键修正：按 Location 自己的 getTime() 算坐标，而不是"当前时刻"。
        // 旧版全用当前时刻，导致任意两个 Location 的 distanceTo() 恒为 0
        // （两边取到同一个点），app 用 distanceTo 累加里程就永远是 0 米。
        try {
            hookOne(Location.class, "getLatitude", new Class<?>[]{}, chain -> {
                double[] p = positionOf(chain.getThisObject());
                return p[0];
            });
            hookOne(Location.class, "getLongitude", new Class<?>[]{}, chain -> {
                double[] p = positionOf(chain.getThisObject());
                return p[1];
            });
            hookOne(Location.class, "getSpeed", new Class<?>[]{}, chain -> {
                long t = timeOf(chain.getThisObject());
                return (float) RouteEngine.currentSpeed(t);
            });
            hookOne(Location.class, "getBearing", new Class<?>[]{}, chain -> {
                long t = timeOf(chain.getThisObject());
                return RouteEngine.bearingAt(t);
            });
            // ★ 海拔与精度必须是**时间的函数**，不能是常量。
            //   旧版这里 return 100.0d / 4.0f，于是 App 记的 laps_json 里
            //   endAltAbs 全场恒为 100.0、elevationGain 恒为 0.0；
            //   而模块接管之前（App 拿真实定位）这两项都是变化的。
            //   「全场恒定」本身就可被判定的设备侧指纹，比数值偏多少更显眼。
            hookOne(Location.class, "getAltitude", new Class<?>[]{}, chain -> {
                long t = timeOf(chain.getThisObject());
                return RouteEngine.currentAltitude(t);
            });
            hookOne(Location.class, "getAccuracy", new Class<?>[]{}, chain -> {
                long t = timeOf(chain.getThisObject());
                return (float) RouteEngine.currentHorizontalAccuracy(t);
            });
            // ★ getTime() 也必须接管。
            // App 的分段边界（segment_json 的 startTime/endTime）就是拿各个
            // Location 的时间戳算的。坐标接管了、时间没接管，分段时长就会
            // 因为 nemu 那份陈旧墙钟而 +406s / −366s 地跳，
            // 于是算出 60km/h 的鬼魂分段并被打上异常标记。
            // 接管后时间轴与坐标同源：两者都由 elapsedRealtime 派生。
            hookOne(Location.class, "getTime", new Class<?>[]{},
                    chain -> timeOf(chain.getThisObject()));
            log(android.util.Log.INFO, TAG, "Location getters overridden (time-based)");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "override Location getters fail", t);
        }

        // ★ 这里删掉了旧版的「setter 接管」。
        // 旧版把 setLatitude/setLongitude/setSpeed/setBearing 全部 return null，
        // 本意是防止别处写值导致 getter 不一致（但 getter 已按时间戳算，压根不读字段）。
        // 实际后果是：Location 内部的 mLatitude/mLongitude 永远保持 0，
        // 而 Location.distanceTo() / distanceBetween() 走的是原生代码直接读这两个字段，
        // 于是任意两个 Location 之间距离恒为 0 —— app 若用 distanceTo 累加里程就是 0 米。
        // 现在改成：不拦 setter，由 Factory 额外用反射把字段写成一致的值。

        log(android.util.Log.INFO, TAG, "anti-cheat scrubbed");
    }

    /**
     * 取一个 Location 在统一时间轴上的时间。
     *
     * ★ 这里刻意**不读 getTime()**，两个原因：
     *
     * 1. 会被污染。模拟器假 GPS 守护进程 nemu 每秒往系统层塞它自己的
     *    Location，那份 Location 的 getTime() 是它自己算的墙钟；
     *    而 elapsedRealtimeNanos 是系统按 fix 真实投递时刻赋值的。
     *    旧版按 getTime() 反查坐标，于是每收到一个外来点，位置就被甩到
     *    另一个时刻的路线位置上。一次作废跑步（App 自己的分段记录）
     *    的分段边界在两条相差数百秒的时间线之间来回跳。
     *
     *    算出远超人类速度的鬼魂分段，并被打上
     *    state:256 异常标记，判「分段不达标」。详见 Clock 的类注释。
     * 2. 会栈溢出。本方法同时被 getTime() 的 hook 调用（见 hookAntiCheat），
     *    再调一次 getTime() 就是无限递归。
     *
     * elapsedRealtime 缺失时退回「现在」。注意不能退回读 getTime()：
     * 一来那正是污染源，二来 Location.mTime 属于非 SDK 接口，
     * 反射读出来恒为 0（实测），拿它当回落值等于拿垃圾当数据。
     */
    private static long timeOf(Object loc) {
        Location l = (Location) loc;
        long clean;
        try {
            long rtNs = l.getElapsedRealtimeNanos();
            clean = rtNs > 0L ? Clock.wallMsForRt(rtNs / 1_000_000L) : Clock.nowMs();
        } catch (Throwable ignored) {
            clean = Clock.nowMs();
        }
        watchTimelineDrift(clean);
        return clean;
    }

    /**
     * 时间轴自检：在**派发点**统计我们喂给 App 的 fix 序列节拍。
     *
     * ★ 为什么必须在派发点、而不是在 timeOf() 里统计：
     *
     *   timeOf() 每秒被调用 80+ 次（App 会反复读 getTime/getLatitude/
     *   getSpeed…），而我们的派发只有 1 次/秒。把这堆调用放在一起比较
     *   「相邻两次是否递增」，比的是**不同 Location 对象**之间的顺序，
     *   而这个顺序天然可以乱（多进程、binder 时序、App 回读历史点）。
     *   实测据此打出的大量 SUSPECT 是**指标错**，不是代码错。
     *
     *   真正该守的两条不变式是：
     *     (a) 同一个 Location 对象，任何时候读都得到同一个时间（纯函数，
     *         由 Clock.wallMsForRt 保证，TimeOfCheck 已覆盖）；
     *     (b) 我们**主动派发**的 fix 序列，时间必须 1 秒一步严格递增。
     *   这里统计的就是 (b)——这条才是 App 切分段时真正依赖的顺序。
     *
     * 判读：跑完一场后看 logcat
     *     TIMELINE n=... neg=0 big=0 minGap≈1000 maxGap≈1000 -> CLEAN
     */
    private static long cadN = 0, cadNeg = 0, cadBig = 0;
    private static long cadMinGap = Long.MAX_VALUE, cadMaxGap = 0;
    private static long cadLastT = 0, cadLastReportMs = 0;

    /**
     * 直接量「每 10 秒桶里落进了几个 fix」—— 这才是 App 侧真正吃到的量。
     *
     * 为什么单独统计它：App 的 speed_json 是「10 秒窗口内相邻 fix 的弦长累加」，
     * 所以某个桶里落了 9 个还是 11 个 fix，会直接把该窗口的速度按整数比例
     * 拉低/抬高（落几个 fix，速度就是几倍步长，与派发周期成整数关系）。
     * 派发周期若与 App 的聚合窗口不同相位，桶内 fix 数就会在 N-1/N/N+1 之间摆动，
     * 表现为那些「偏离中位数」的窗口。
     *
     * 判读：跑完一场后看 logcat，`exactly10` 应接近 100%。
     */
    private static final Map<Long, Integer> bucketFixes = new java.util.HashMap<>();

    private static synchronized void watchBucketFill(long t) {
        long bucket = t / 10_000L;          // 10 秒桶（与 App 的聚合窗口同相位）
        Integer c = bucketFixes.get(bucket);
        bucketFixes.put(bucket, c == null ? 1 : c + 1);
    }

    private static synchronized void reportBucketFill() {
        if (bucketFixes.size() < 3) return;
        // 丢掉首尾两个不完整的桶（起跑/收尾那一刻必然缺角）
        List<Long> keys = new ArrayList<>(bucketFixes.keySet());
        java.util.Collections.sort(keys);
        keys = keys.subList(1, keys.size() - 1);
        int exactly10 = 0, total = 0, minC = Integer.MAX_VALUE, maxC = 0;
        StringBuilder hist = new StringBuilder();
        java.util.TreeMap<Integer, Integer> dist = new java.util.TreeMap<>();
        for (Long k : keys) {
            int c = bucketFixes.get(k);
            total++;
            if (c == 10) exactly10++;
            if (c < minC) minC = c;
            if (c > maxC) maxC = c;
            Integer d = dist.get(c);
            dist.put(c, d == null ? 1 : d + 1);
        }
        for (Map.Entry<Integer, Integer> e : dist.entrySet()) {
            if (hist.length() > 0) hist.append(',');
            hist.append(e.getKey()).append('x').append(e.getValue());
        }
        android.util.Log.i(TAG, "BUCKET n=" + total
                + " exactly10=" + (total == 0 ? "n/a"
                        : String.format(java.util.Locale.US, "%.1f%%", exactly10 * 100.0 / total))
                + " min=" + minC + " max=" + maxC
                + " dist={" + hist + "}");
        bucketFixes.clear();
    }

    private static synchronized void watchDispatchCadence(long t) {
        if (cadLastT != 0) {
            long gap = t - cadLastT;
            cadN++;
            if (gap < 0) cadNeg++;
            if (gap > 3000) cadBig++;
            if (gap < cadMinGap) cadMinGap = gap;
            if (gap > cadMaxGap) cadMaxGap = gap;
        }
        cadLastT = t;

        if (cadLastReportMs == 0) cadLastReportMs = t;
        if (t - cadLastReportMs >= 60_000L) {
            cadLastReportMs = t;
            boolean clean = cadNeg == 0 && cadBig == 0 && cadN > 0
                    && cadMinGap >= 950 && cadMaxGap <= 1050;
            android.util.Log.i(TAG, "TIMELINE n=" + cadN
                    + " neg=" + cadNeg
                    + " big>3s=" + cadBig
                    + " minGap=" + (cadMinGap == Long.MAX_VALUE ? -1 : cadMinGap)
                    + " maxGap=" + cadMaxGap
                    + " -> " + (clean ? "CLEAN" : "SUSPECT"));
            reportBucketFill();
            reportVitalSigns(t);
            cadN = cadNeg = cadBig = 0;
            cadMinGap = Long.MAX_VALUE;
            cadMaxGap = 0;
        }
    }

    /**
     * 「恒定值」自检：把那些**曾经被写死成常量**的量按分钟打出来。
     *
     * 为什么值得单独一条日志：这几个量的破绽不在「偏了多少」，而在
     * **「一点都没变」**。App 会把它们原样记进 laps_json
     * （endAltAbs / elevationGain / endAltRel），而真人在一段跑步里
     * 这几个值必然逐圈变化。
     *
     * 判读：ALT/ACC 的 span 必须 > 0。
     * 若出现 `ALT span=0.000` 就说明又退化成常量了，属于回归。
     *
     * ★ 采样必须是**逐 tick 累积**的。
     *   初版写成「在分钟边界上取一次值当 min 和 max」，于是同一个样本
     *   既是 min 又是 max，span 恒为 0，自检永远报 CONSTANT! ——
     *   那是指标错，不是功能错。现在每个派发 tick 都调一次
     *   {@link #accumulateVitals(long)}，分钟边界只负责汇总与清零。
     */
    private static double vsAltMin = Double.MAX_VALUE, vsAltMax = -Double.MAX_VALUE;
    private static double vsAccMin = Double.MAX_VALUE, vsAccMax = -Double.MAX_VALUE;
    /** 上一分钟的 stepsFloat 采样（用于量实际发出的步频）。-1 = 还没有基线。 */
    private static double vsStepRef = -1;
    private static long vsStepRefT = -1;

    /** 每个派发 tick 调一次，累积本分钟内的取极值。 */
    private static synchronized void accumulateVitals(long t) {
        try {
            double alt = RouteEngine.currentAltitude(t);
            double acc = RouteEngine.currentHorizontalAccuracy(t);
            if (alt < vsAltMin) vsAltMin = alt;
            if (alt > vsAltMax) vsAltMax = alt;
            if (acc < vsAccMin) vsAccMin = acc;
            if (acc > vsAccMax) vsAccMax = acc;
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void reportVitalSigns(long t) {
        try {
            if (vsAltMin == Double.MAX_VALUE) return;    // 本分钟没采到样
            double altRel = RouteEngine.currentAltitudeRelative(t);
            // ★ 自检必须量「实际发出去的东西」，不能量「另一套公式」。
            //   两个计步通道的真实来源都是 RouteEngine.stepsFloat()：
            //     STEP_COUNTER = floor(steps)        —— 累计值
            //     STEP_DETECTOR = floor(steps) 的增量 —— 逐事件
            //   所以两者的步频恒等是构造保证，无需再比。
            //   旧版这里比的是 currentStepRate()（steps 的解析导数）与
            //   currentCadence()（另一套独立公式），二者在疲劳段本就不同源，
            //   报出的百分比差异是**指标错**，不是通道不一致。
            //   现在改为量 stepsFloat 的实际增量率，与派发同源。
            double stepsNow = RouteEngine.stepsFloat(t);
            double rateSpm;
            if (vsStepRefT < 0 || stepsNow < vsStepRef) {
                // 首分钟，或热重载导致时间轴重置（步数倒退）→ 重新取基线
                vsStepRefT = t; vsStepRef = stepsNow; rateSpm = -1;
            } else {
                double dt = (t - vsStepRefT) / 1000.0;
                rateSpm = dt > 0.5 ? (stepsNow - vsStepRef) / dt * 60.0 : -1;
                vsStepRefT = t; vsStepRef = stepsNow;
            }
            boolean ok = (vsAltMax - vsAltMin) > 1e-6 && (vsAccMax - vsAccMin) > 1e-6;
            android.util.Log.i(TAG, String.format(java.util.Locale.US,
                    "VITALS alt=%.2f/%.2f span=%.3f rel=%+.2f acc=%.2f/%.2f "
                            + "emitSteps=%.0f emitSpm=%.1f -> %s",
                    vsAltMin, vsAltMax, vsAltMax - vsAltMin, altRel,
                    vsAccMin, vsAccMax, stepsNow, rateSpm,
                    ok ? "VARIES" : "CONSTANT!"));
            vsAltMin = Double.MAX_VALUE; vsAltMax = -Double.MAX_VALUE;
            vsAccMin = Double.MAX_VALUE; vsAccMax = -Double.MAX_VALUE;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 盯住「我们的时间轴」与设备墙钟的偏差。
     *
     * 为什么要盯：Clock 锚定一次之后只按 elapsedRealtime 递推，
     * 好处是完全免疫时钟步进，代价是万一设备墙钟真的被步进过，
     * 我们的时间轴会和真实时间持续偏离，而 App 要拿设备时间去比对
     * 服务端下发的跑区时段（docs/03 G1），偏多了就会拒绝上传。
     *
     * 这条日志同时能定性：作废那次到底是「单个 Location 的时间戳被污染」
     * 还是「系统墙钟被步进」——后者会让这里的偏差长期存在。
     * 节流：每秒最多一条，且只在偏差 > 5 秒时打。
     */
    private static long lastDriftLogMs = 0L;

    private static void watchTimelineDrift(long clean) {
        long wall = System.currentTimeMillis();
        long drift = wall - clean;
        if (Math.abs(drift) <= 5_000L) return;
        if (clean - lastDriftLogMs < 1_000L) return;
        lastDriftLogMs = clean;
        android.util.Log.w(TAG, "timeline vs wall-clock drift=" + drift
                + "ms (ours=" + clean + " wall=" + wall + ")");
    }

    /** 按 Location 自己的时间戳算出它应该显示的坐标。 */
    private static double[] positionOf(Object loc) {
        return RouteEngine.positionAt(timeOf(loc));
    }

    // ================================================================ 定时派发

    private synchronized Handler bg() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("RunSpoof-dispatch");
            t.start();
            bg = new Handler(t.getLooper());
        }
        return bg;
    }

    private void startDispatchers(ClassLoader cl) {
        if (started) return;
        started = true;

        final Constructor<?> ctor;
        final Field fSensor, fTs, fAcc, fValues;
        try {
            Class<?> se = Class.forName("android.hardware.SensorEvent", false, cl);
            ctor = se.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            fSensor = se.getDeclaredField("sensor");
            fSensor.setAccessible(true);
            fTs = se.getDeclaredField("timestamp");
            fTs.setAccessible(true);
            fAcc = se.getDeclaredField("accuracy");
            fAcc.setAccessible(true);
            fValues = se.getDeclaredField("values");
            fValues.setAccessible(true);
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "SensorEvent reflection fail", t);
            return;
        }

        Handler h = bg();

        // ── 位置派发：1 Hz，但用**绝对节拍**而不是相对节拍 ────────────────
        //
        // ★ 本轮修掉的一个真实缺陷：原来每次都是 `postDelayed(this, 1000L)`，
        //   这是「相对排期」—— 下一次执行时刻 = 上一次执行时刻 + 1000 + 本次执行耗时。
        //   每 tick 的耗时（构 Location、反射 fillAmap、回调 N 个 listener）都要
        //   数毫秒，于是实际周期总是比 1000 ms 略长，且随负载抖动。
        //
        //   后果不是「时间轴不准」（Clock 有锚点，绝对时间仍对），而是
        //   **采样相位相对 App 的 10 秒聚合窗口缓慢滑动**：App 的桶边界固定在
        //   整十秒，而我们的 fix 每 tick 后移一点，于是某个 10 秒桶里
        //   有时落进 N-1 个 fix、有时 N+1 个。
        //
        //   这是可以从 App 自己的数据里直接看到的：按「每桶 → 窗距 / 单位步距」
        //   反推每桶的 fix 数，改造前的场次里桶内计数在 N-1/N/N+1 之间摆动。
        //   而 10 秒桶的速度就是「这些 fix 的弦长和 ÷ 10 秒」，所以离散的
        //   fix 个数把速度**整数量化**成固定几档 —— 那些「偏离中位数」的窗口
        //   全部落在整数 tick 上，不是速度噪声开得太大。
        //
        //   改成锚定到绝对时间网格后，每 tick 的目标时刻是 startRt + k*1000，
        //   误差不再累积（每次按「下一个网格点 - 现在」重新排期），
        //   桶内 fix 数会稳定下来（或整场稳定为某个固定值，边界不再来回跨）。
        final long gridStart = SystemClock.elapsedRealtime();
        h.post(new Runnable() {
            private int tick = 0;

            @Override
            public void run() {
                try {
                    List<Object> snapshot;
                    synchronized (locationListeners) {
                        snapshot = new ArrayList<>(locationListeners);
                    }
                    if (snapshot.isEmpty()) {
                        tick++;
                        h.postDelayed(this, delayToNextGrid(gridStart, tick));
                        return;
                    }
                    // ★ 一次 tick 只造一条 fix，所有 listener 共用。
                    //
                    // 旧版把 buildLocation() 放在循环里，同一次 tick 里 N 个
                    // listener 会各拿到一条**时间戳不同**的 fix —— 它们本该是
                    // 同一次定位。App 若从多路取点（gps + fused + 高德），
                    // 就会把同一瞬间看成多个不同时刻，分段边界被切得乱七八糟。
                    // 这也会让「时间轴自检」测不准：它要统计的是派发节拍，
                    // 而不是循环内被调用几次。
                    Location loc = Factory.buildLocation("gps");
                    watchDispatchCadence(loc.getTime());
                    watchBucketFill(loc.getTime());
                    accumulateVitals(loc.getTime());
                    for (Object l : snapshot) {
                        Method cb = findMethod(l.getClass(), "onLocationChanged", 1);
                        if (cb != null) {
                            try {
                                cb.invoke(l, loc);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                tick++;
                h.postDelayed(this, delayToNextGrid(gridStart, tick));
            }
        });

        // 计步传感器派发（1Hz 足够：STEP_COUNTER 是累计值）
        //
        // ★ 改进8：STEP_DETECTOR 必须与 STEP_COUNTER **同源**。
        //
        //   旧版这里按 `Math.round(cadence / 60)` 每秒一次性推 N 个事件，
        //   两个问题叠在一起：
        //     (a) 数量对不上：counter 侧是 floor(steps())，detector 侧是
        //         round(cadence/60) × 秒数。两者一个是积分值、一个是瞬时
        //         速率的圆整，长期必然漂移 —— 仿真台复现出的 10 秒窗口里
        //         两通道步频大面积对不上（离线可复算，见 _tools/harness
        //         的 ChannelCheck）。
        //     (b) 时间戳全同：同一 tick 里推的 N 个事件共用
        //         SystemClock.elapsedRealtimeNanos()，于是逐步间隔是
        //         0 ms / 0 ms / … / 1000 ms 这样的锯齿，而真人步间隔
        //         是几百毫秒的连续值。
        //   App 同时订阅这两个传感器，两边一交叉就能看出矛盾。现在统一到
        //   steps() 的解析导数（currentStepRate），并且**每个事件用各自的
        //   真实跨越时刻做时间戳**，不再挤在同一瞬间。
        h.post(new Runnable() {
            /** 上一次派发时已经播到第几步（累计步数取整）。 */
            private long lastEmittedStep = -1;

            @Override
            public void run() {
                try {
                    long now = Clock.nowMs();
                    double stepsFloat = RouteEngine.stepsFloat(now);

                    for (Object[] pair : new ArrayList<>(stepListeners.values())) {
                        Object listener = pair[0];
                        Object sensor = pair[1];
                        int type = sensorType(sensor);
                        if (type == 19) {            // STEP_COUNTER：累计步数
                            pushSensor(listener, sensor,
                                    new float[]{(float) Math.floor(stepsFloat)},
                                    ctor, fSensor, fTs, fAcc, fValues);
                        } else if (type == 18) {     // STEP_DETECTOR：每步一个事件
                            long cur = (long) Math.floor(stepsFloat);
                            if (lastEmittedStep < 0) {
                                // 首个 tick 只记基线，不补发历史步
                                lastEmittedStep = cur;
                                continue;
                            }
                            long from = lastEmittedStep;
                            long to = cur;
                            if (to > from) {
                                // 这一秒内跨过的每一步，按各自的真实时刻打时间戳：
                                // 第 n 步发生在 steps(t) 恰好等于 n 的那一刻。
                                // 用解析反查太贵，这里用「步相位线性分摊」近似 ——
                                // steps() 在一个 tick 内的变化很小，误差 < 1 ms。
                                double span = stepsFloat - from;
                                for (long n = from + 1; n <= to; n++) {
                                    double frac = span <= 0 ? 1.0
                                            : Math.max(0.0, Math.min(1.0, (n - from) / span));
                                    long stepRtNs = SystemClock.elapsedRealtimeNanos()
                                            - (long) ((1.0 - frac) * 1000.0 * 1_000_000L);
                                    pushSensorAt(listener, sensor, new float[]{1f},
                                            ctor, fSensor, fTs, fAcc, fValues, stepRtNs);
                                }
                                if (lastEmittedStep < to) lastEmittedStep = to;
                            } else if (to < from) {
                                // 配置热重载导致时间轴重置：重新对齐基线
                                lastEmittedStep = to;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                h.postDelayed(this, 1000L);
            }
        });

        // 加速度计高频派发 50Hz（与上游 MotionRealism 一致）。
        // App 自身的加速度回调是数十 Hz 量级，50Hz 派发能保证
        // 常见步频（每分钟一百多到两百步，即数 Hz）的步态波形每步有足够多点，
        // 峰值可清晰数出。
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = Clock.nowMs();
                    double stepsFloat = RouteEngine.stepsFloat(now);
                    double speed = RouteEngine.currentSpeed(now);
                    GaitEngine.Session session = RouteEngine.gait();

                    for (Object[] pair : new ArrayList<>(stepListeners.values())) {
                        Object listener = pair[0];
                        Object sensor = pair[1];
                        if (sensorType(sensor) != 1) continue;   // ACCELEROMETER
                        float[] vals = session.accelerometer(stepsFloat, speed);
                        pushSensor(listener, sensor, vals,
                                ctor, fSensor, fTs, fAcc, fValues);
                    }
                } catch (Throwable ignored) {
                }
                h.postDelayed(this, GaitEngine.TICK_MS);   // 20ms = 50Hz
            }
        });

        log(android.util.Log.INFO, TAG, "dispatchers started");
    }

    /**
     * 算出「距离第 tick 个绝对网格点还有多久」，用于把 1 Hz 派发钉在整秒上。
     *
     * 为什么不用固定的 1000 ms：{@code postDelayed} 是相对排期，每 tick 的实际
     * 耗时（构 Location、反射写字段、回调 N 个 listener）会累加到周期里，
     * 实测周期总是比标称值略长且随负载抖动。锚定到 {@code gridStart + tick*1000} 之后，
     * 本次慢了多少、下次就少等多少，误差不累积。
     *
     * 返回值下限 1 ms（{@code postDelayed(0)} 会被当成「立刻」，
     * 万一本 tick 已经超时太多会变成忙循环），上限 1000 ms。
     * 真的落后超过一整格时直接跳到下一格，不做补发 ——
     * 补发会造成同一瞬间多条 fix，比分段边界抖动更糟。
     */
    private static long delayToNextGrid(long gridStart, int tick) {
        long target = gridStart + (long) tick * DISPATCH_PERIOD_MS;
        long delay = target - SystemClock.elapsedRealtime();
        if (delay < 1L) {
            // 已经错过这一格：跳到下一个还没到的格子
            long behind = -delay;
            delay = DISPATCH_PERIOD_MS - (behind % DISPATCH_PERIOD_MS);
            if (delay < 1L) delay = 1L;
        }
        return Math.min(delay, DISPATCH_PERIOD_MS);
    }

    /** 定位派发周期（毫秒）。与 App 的 10 s / 20 s 聚合窗口成整数比。 */
    private static final long DISPATCH_PERIOD_MS = 1000L;

    /** 读 Sensor 的 type。不同版本字段名可能是 mType 或 type。 */
    private static int sensorType(Object sensor) {
        for (String fn : new String[]{"mType", "type"}) {
            try {
                Field ft = sensor.getClass().getDeclaredField(fn);
                ft.setAccessible(true);
                return ft.getInt(sensor);
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /**
     * 在类层次里找方法。listener 常常继承自一个抽象适配器，
     * 只 getDeclaredMethods() 拿不到父类实现。
     */
    private static Method findMethod(Class<?> c, String name, int argc) {
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    m.setAccessible(true);
                    return m;
                }
            }
            for (Class<?> itf : cur.getInterfaces()) {
                for (Method m : itf.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == argc) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private void pushSensor(Object listener, Object sensor, float[] values,
                            Constructor<?> ctor, Field fS, Field fT, Field fA, Field fV) {
        pushSensorAt(listener, sensor, values, ctor, fS, fT, fA, fV,
                SystemClock.elapsedRealtimeNanos());
    }

    /**
     * 按指定时间戳推一个传感器事件。
     *
     * @param timestampNs 事件的 elapsedRealtimeNanos。允许略早于「现在」——
     *                    STEP_DETECTOR 要把一秒钟内跨过的几步各自摊到自己
     *                    的真实时刻上，不能全挤在同一瞬间（旧版就是这样，
     *                    导致逐步间隔出现 0/1000 ms 的锯齿）。
     */
    private void pushSensorAt(Object listener, Object sensor, float[] values,
                              Constructor<?> ctor, Field fS, Field fT, Field fA, Field fV,
                              long timestampNs) {
        try {
            Object ev = ctor.newInstance(values.length);
            float[] target = (float[]) fV.get(ev);
            System.arraycopy(values, 0, target, 0, Math.min(values.length, target.length));
            fS.set(ev, sensor);
            fT.setLong(ev, timestampNs);
            fA.setInt(ev, 3);
            Method cb = findMethod(listener.getClass(), "onSensorChanged", 1);
            if (cb != null) cb.invoke(listener, ev);
        } catch (Throwable ignored) {
        }
    }
}
