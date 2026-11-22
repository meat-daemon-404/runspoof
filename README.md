# RunSpoof · 位置与步态运动学仿真引擎（公开脱敏技术架构版）

[![Platform](https://img.shields.io/badge/Platform-Android_LSPosed_(API_29--35)-3DDC84?logo=android&logoColor=white)](https://github.com/meat-daemon-404/runspoof)
[![Java 17](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)](https://github.com/meat-daemon-404/runspoof)
[![Privacy](https://img.shields.io/badge/Sanitized-Zero_Personal_or_School_Data-00b894)](https://github.com/meat-daemon-404/runspoof)

> **Android 传感器与位置时间轴一致性仿真研究模块（公开脱敏版）** —— 聚焦 Android `LocationManager` / 高德 `AMapLocationClient` / `SensorManager`（`TYPE_STEP_COUNTER` & `TYPE_STEP_DETECTOR`）多源时钟同步、闭合二次 B 样条轨迹平滑与步态动力学耦合的纯技术研究实现。本公开仓库已彻底剥离所有个人轨迹、学校坐标与实测抓包日志。

## 核心仿真架构（`src/`）

| 核心模块 | 类名 | 关键数学与工程机理 |
| :--- | :--- | :--- |
| **纯仿射单调时钟** | `Clock` | 对 `System.currentTimeMillis()`、`SystemClock.elapsedRealtime()`、`elapsedRealtimeNanos()` 以及 `Location.getTime()` / `getElapsedRealtimeNanos()` 施加同源仿射变换 $t' = t_0 + s \cdot (t - t_0)$，消除跨线程时间跳变与末尾时钟漂移 |
| **8 轮迭代坐标反演** | `Geo` | 提供 WGS-84 与 GCJ-02 坐标系的正向转换及 **8 轮不动点迭代逆变换**，反演残差控制在 $< 10^{-9}$ 度（亚毫米级） |
| **闭合二次 B 样条轨迹引擎** | `RouteEngine` | 对多边形折线航点进行闭合二次 B 样条（Quadratic B-Spline）曲率连续平滑，将拐角 p99 转向角压降至 `< 19°`，并按 10 秒绝对时间网格（`BUCKET exactly10=100%`）稳定派发位置采样 |
| **双通道解析导数步态引擎** | `GaitEngine` | 通过解析导数 `stepRate(t)` 严格耦合瞬时配速、步幅与步频（`TYPE_STEP_COUNTER` 累积步数与 `TYPE_STEP_DETECTOR` 脉冲事件同源驱动），并同步合成基于路径弧长的连续周期海拔 `altBaseM` 与动态水平精度 `accuracy` |
| **统一反射钩子工厂** | `Factory` / `HookEntry` | 零侵入接管系统定位回调、传感器监听器与休眠唤醒锁，配合 `ConfigStore` 与可视化选点器 `MapPickerActivity` 实现热更新参数调节 |

## 目录结构

```text
runspoof/
├── src/                         # 核心 Java 源码（Clock / Geo / RouteEngine / GaitEngine / Factory / HookEntry）
├── res/                         # 模块配置界面与选点器布局资源
├── docs/                        # 00–06 全套运动学仿真机理、时钟一致性分析与排错技术文档
├── AndroidManifest.xml          # Xposed / LSPosed 模块元数据声明
└── build.ps1                    # Windows PowerShell 零 Gradle 依赖一键编译、DEX 打包与签名脚本
```

## 快速构建

在配置好 Android SDK Build-Tools 与 JDK 17 的 Windows 环境中：

```powershell
# 一键完成 javac 编译 -> d8 转 DEX -> aapt2 资源链接 -> apksigner V1+V2 签名
.\build.ps1
```

## 合规与研究声明

本仓库仅用于 **Android 位置服务测试、传感器多源时钟一致性建模与轨迹平滑算法研究**，不包含任何针对特定高校或商业平台的绕过凭据与真实地理坐标。
