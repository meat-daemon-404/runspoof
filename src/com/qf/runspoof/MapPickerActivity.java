package com.qf.runspoof;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 路线编辑器。
 *
 * 设计目标很朴素：**让人能在卫星/街道图上把操场跑道描一圈**。
 *
 * 上一版做不到这件事，原因是：
 *   1. 只能「点一下加一个点」，点之间没有连线，看不出自己画了什么；
 *   2. 点错了只能清空重来，没有撤销、没有拖动改点；
 *   3. 编辑和保存混在一起：点地图就直接写盘，稍一误触就把配置改了。
 *
 * 这一版把「编辑」和「保存」彻底分开：
 *   编辑期所有改动只在 WebView 内的 path 数组里，不落盘；
 *   只有按「保存」时，才把路径 + 参数一起写进模块配置。
 *
 * 支持的编辑动作：
 *   · 点击空白处追加一个路径点
 *   · 长按已有点删除它
 *   · 拖动已有点微调位置
 *   · 撤销 / 清空
 *   · 一键「跑道圆环」：以当前中心为圆心按半径生成一圈点，
 *     这样不用手描也能得到规整的跑道轨迹
 */
public class MapPickerActivity extends Activity {

    private WebView web;
    private TextView info;
    private TextView stats;
    private boolean pickMode = false;

    private EditText pendingRadius, pendingSpeed, pendingCadence, pendingAlt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ── 顶部信息 ────────────────────────────────────────────────
        info = new TextView(this);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setPadding(24, 14, 24, 6);
        info.setText("加载中…");
        root.addView(info);

        // ── 按钮行 1：路径编辑 ──────────────────────────────────────
        LinearLayout bar1 = new LinearLayout(this);
        bar1.setOrientation(LinearLayout.HORIZONTAL);
        bar1.setPadding(10, 0, 10, 6);

        Button btnPick = addBtn(bar1, "定中心");
        btnPick.setOnClickListener(v -> {
            pickMode = !pickMode;
            btnPick.setText(pickMode ? "点地图…" : "定中心");
            info.setText(pickMode ? "点地图上任意位置，把它设为路线中心" : "已退出选中心模式");
            js("setPickMode(" + pickMode + ")");
        });

        Button btnUndo = addBtn(bar1, "撤销");
        btnUndo.setOnClickListener(v -> js("undo()"));

        Button btnRing = addBtn(bar1, "跑道圈");
        btnRing.setOnClickListener(v -> js("makeRing()"));

        Button btnClear = addBtn(bar1, "清空");
        btnClear.setOnClickListener(v -> {
            js("clearPath()");
            info.setText("路径已清空（还没保存）");
        });
        root.addView(bar1);

        // ── 参数行 ──────────────────────────────────────────────────
        LinearLayout params = new LinearLayout(this);
        params.setOrientation(LinearLayout.HORIZONTAL);
        params.setPadding(10, 0, 10, 6);
        pendingRadius = addLabeledEdit(params, "半径(m)", "39");
        pendingSpeed = addLabeledEdit(params, "速度(m/s)", "2.4");
        pendingCadence = addLabeledEdit(params, "步频(SPM)", "172");
        pendingAlt = addLabeledEdit(params, "海拔(m)", "");
        root.addView(params);

        // ── 海拔提示 ────────────────────────────────────────────────
        // 海拔必须按自己学校所在位置填，不能留空用默认值：
        //   留空时会退回一个兜底常量，而「所有使用者上报同一个海拔」
        //   本身就是可跨账号聚合的指纹；填错量级还会与当地地形对不上。
        // 留空 = 不写这一项（保持向后兼容，老配置文件行为不变）。
        TextView altHint = new TextView(this);
        altHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        altHint.setTextColor(0xFF666666);
        altHint.setPadding(24, 0, 24, 6);
        altHint.setText("海拔：填自己所在位置的大致海拔（米）。留空则用兜底值，"
                + "但强烈建议填 —— 全场海拔恒定是明显的设备侧特征。");
        root.addView(altHint);

        // ── 按钮行 2：保存 / 重置 ───────────────────────────────────
        LinearLayout bar2 = new LinearLayout(this);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.setPadding(10, 0, 10, 6);

        Button btnSave = addBtn(bar2, "保存配置");
        btnSave.setOnClickListener(v -> save());

        Button btnReset = addBtn(bar2, "恢复默认");
        btnReset.setOnClickListener(v -> {
            ConfigStore.Config d = new ConfigStore.Config();
            ConfigStore.writeUi(this, d);
            applyToUi(d);
            js("loadPath('" + d.pathString() + "'," + d.centerLat + "," + d.centerLng + ")");
            refreshInfo(d, "已恢复默认");
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
        });
        root.addView(bar2);

        // ── 统计行 ──────────────────────────────────────────────────
        stats = new TextView(this);
        stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        stats.setTextColor(0xFF333333);
        stats.setPadding(24, 2, 24, 6);
        stats.setText("路径 0 点");
        root.addView(stats);

        // ── 地图 ────────────────────────────────────────────────────
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                ConfigStore.Config c = ConfigStore.readLocal(MapPickerActivity.this);
                js("loadPath('" + c.pathString() + "'," + c.centerLat + "," + c.centerLng + ")");
                runOnUiThread(() -> {
                    stats.setText(describePath(c));
                    refreshInfo(c, null);
                });
            }
        });
        web.loadDataWithBaseURL("https://webrd01.is.autonavi.com/", HTML,
                "text/html", "UTF-8", null);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        ConfigStore.Config c = ConfigStore.readLocal(this);
        applyToUi(c);
        refreshInfo(c, null);
        // ★ 初始也要刷一次路径统计。原来只在 onPath 回调里刷，
        //   于是带着已存路线打开界面时，Map 上画着 19 个点、
        //   顶部却还是「路径 0 点」，看着像没读进来。
        stats.setText(describePath(c));
    }

    // ================================================================ 数据

    private ConfigStore.Config readForm() {
        ConfigStore.Config c = ConfigStore.readLocal(this);
        c.radiusM = parseOr(pendingRadius, c.radiusM);
        c.speedMps = parseOr(pendingSpeed, c.speedMps);
        c.cadenceSpm = parseOr(pendingCadence, c.cadenceSpm);
        // 海拔：留空 = 保持原值（默认 NaN = 没设过）。
        // 注意不能用 parseOr(..., 0)，那会把「留空」写成海平面 0 m。
        c.altBaseM = parseOr(pendingAlt, c.altBaseM);
        return c.clamp();
    }

    private static double parseOr(EditText e, double def) {
        if (e == null) return def;
        try {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) return def;
            return Double.parseDouble(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private void applyToUi(ConfigStore.Config c) {
        if (pendingRadius != null) pendingRadius.setText(String.valueOf((int) c.radiusM));
        if (pendingSpeed != null) pendingSpeed.setText(String.valueOf(c.speedMps));
        if (pendingCadence != null) pendingCadence.setText(String.valueOf((int) c.cadenceSpm));
        if (pendingAlt != null) {
            // 没设过时留空，而不是显示一个看起来像真值的数字
            pendingAlt.setText(Double.isNaN(c.altBaseM) ? "" : String.valueOf((int) c.altBaseM));
        }
    }

    /**
     * ★ 把中心点对齐到路径的几何中心。
     *
     * 为什么需要这一步：中心点原本只有按「定中心」才会更新。
     * 但用户的实际操作往往是直接「跑道圈」或手描一圈就保存，
     * 于是配置里出现「路径画在 A 场地、中心还在 B 场地」的情况——两者可能差一两公里。
     * 中心点参与 RouteEngine 的兜底与界面显示，长期不一致会让人
     * 以为路线没生效，也会让「恢复默认」把视野拉回错误的位置。
     *
     * 规则：路径有 ≥2 个点时，中心点 = 路径顶点均值；否则保留原中心。
     */
    private static void alignCenterToPath(ConfigStore.Config c) {
        if (c.path == null || c.path.size() < 2) return;
        double lat = 0, lng = 0;
        for (double[] p : c.path) {
            lat += p[0];
            lng += p[1];
        }
        c.centerLat = lat / c.path.size();
        c.centerLng = lng / c.path.size();
    }

    private void refreshInfo(ConfigStore.Config c, String prefix) {
        String txt = String.format(Locale.US,
                "中心 %.6f, %.6f ｜ 配速 %s ｜ 步频 %d",
                c.centerLat, c.centerLng, c.paceText(), (int) c.cadenceSpm);
        info.setText(prefix == null ? txt : prefix + "\n" + txt);
    }

    /** 只有按这个按钮才会真正写配置。 */
    private void save() {
        ConfigStore.Config cfg = readForm();
        // 保存前把中心点对齐到路径，避免"路径在 A、中心在 B"
        alignCenterToPath(cfg);
        boolean ok = ConfigStore.writeUi(this, cfg);
        applyToUi(cfg);
        String msg = ok ? "已保存" : "保存失败";
        Toast.makeText(this, msg + "：路径 " + cfg.path.size() + " 点，配速 "
                + cfg.paceText() + "，步频 " + (int) cfg.cadenceSpm, Toast.LENGTH_LONG).show();
        refreshInfo(cfg, msg + "（" + cfg.path.size() + " 个路径点）");

        // ★ 文件通道没通的时候必须明说，否则用户会以为保存成功、
        //   然后在目标 App 里发现位置压根没变，白排查半天。
        if (!ok) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("配置没能生效")
                    .setMessage("模块读配置走的是 " + ConfigStore.fileHint()
                            + "\n\n这个文件必须先用 root 建好并放开权限，"
                            + "模块界面（普通 app 权限）自己建不了。\n\n"
                            + "在电脑上执行上面那条命令，再回来按一次「保存配置」即可。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    // ================================================================ 控件

    private Button addBtn(LinearLayout bar, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(4, 0, 4, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(3, 0, 3, 0);
        bar.addView(b, lp);
        return b;
    }

    private EditText addLabeledEdit(LinearLayout row, String label, String def) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMargins(4, 0, 4, 0);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(0xFF666666);
        tv.setGravity(Gravity.CENTER);
        col.addView(tv);

        EditText e = new EditText(this);
        e.setText(def);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setGravity(Gravity.CENTER);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        col.addView(e);

        row.addView(col, clp);
        return e;
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    // ================================================================ JS 桥

    private class Bridge {
        /** 选中心。会同时更新中心点和「跑道圈」的基准。 */
        @JavascriptInterface
        public void onCenter(final double lat, final double lng) {
            runOnUiThread(() -> {
                ConfigStore.Config c = ConfigStore.readLocal(MapPickerActivity.this);
                c.centerLat = lat;
                c.centerLng = lng;
                ConfigStore.writeUi(MapPickerActivity.this, c);
                pickMode = false;
                js("setPickMode(false)");
                refreshInfo(c, String.format(Locale.US, "中心已选 %.6f, %.6f", lat, lng));
            });
        }

        /**
         * 路径变化。只更新内存里的 path，不写盘 —— 保存按钮才写。
         *
         * 但中心点要跟着路径走：一旦路径有 ≥2 个点，就把中心点重新对齐到
         * 路径几何中心。否则用户直接「跑道圈」而不按「定中心」，
         * 中心点会一直停在旧位置（差 1~2 公里），界面上却看不出问题。
         */
        @JavascriptInterface
        public void onPath(final String csv) {
            runOnUiThread(() -> {
                ConfigStore.Config c = ConfigStore.readLocal(MapPickerActivity.this);
                c.setPathString(csv);
                alignCenterToPath(c);          // 中心点跟着路径走
                ConfigStore.writeUi(MapPickerActivity.this, c);
                stats.setText(describePath(c));
                refreshInfo(c, null);
            });
        }

        @JavascriptInterface
        public void onInfo(final String msg) {
            runOnUiThread(() -> stats.setText(msg));
        }

        /**
         * 供 JS 的「跑道圈」读取当前半径。
         * 直接读输入框而不是读配置：用户刚改过半径还没保存时，
         * 生成的圈也应该按新半径来。
         */
        @JavascriptInterface
        public double radiusM() {
            return parseOr(pendingRadius, 39.0);
        }
    }

    /** 把路径换算成「多少点、总长多少米」，让人一眼判断画得对不对。 */
    private String describePath(ConfigStore.Config c) {
        if (c.path == null || c.path.size() < 2) {
            return "路径 " + (c.path == null ? 0 : c.path.size()) + " 点（至少 2 点才会按折线跑）";
        }
        double total = 0;
        for (int i = 1; i < c.path.size(); i++) {
            total += dist(c.path.get(i - 1), c.path.get(i));
        }
        // 闭合回起点
        total += dist(c.path.get(c.path.size() - 1), c.path.get(0));
        return String.format(Locale.US, "路径 %d 点 ｜ 绕一圈 %.0f 米", c.path.size(), total);
    }

    private static double dist(double[] a, double[] b) {
        double dLat = (b[0] - a[0]) * 111320.0;
        double dLng = (b[1] - a[1]) * 111320.0 * Math.cos(Math.toRadians(a[0]));
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ================================================================ HTML / JS

    /**
     * 自带瓦片地图，不依赖外部 JS 库（Leaflet 从 unpkg 拉，国内常白屏）。
     *
     * 编辑能力全在前端：画线、标号、拖动改点、长按删除、撤销栈。
     * 后端只负责在「保存」时把结果写进配置。
     */
    private static final String HTML =
            "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
            "<style>" +
            "html,body{margin:0;padding:0;height:100%;overflow:hidden;background:#e9e9e9;" +
            "font-family:sans-serif;-webkit-user-select:none;user-select:none}" +
            "#map{position:absolute;inset:0;overflow:hidden;touch-action:none;background:#dfe6e9}" +
            ".tile{position:absolute;width:256px;height:256px;pointer-events:none;opacity:.95}" +
            "#svg{position:absolute;inset:0;pointer-events:none}" +
            ".vtx{position:absolute;width:20px;height:20px;margin:-10px 0 0 -10px;border-radius:50%;" +
            "background:#1e88e5;color:#fff;font-size:11px;line-height:20px;text-align:center;" +
            "box-shadow:0 1px 3px rgba(0,0,0,.4);pointer-events:none;font-weight:bold}" +
            ".vtx.first{background:#43a047}" +
            ".ctrpin{position:absolute;width:0;height:0;margin:-10px 0 0 -10px;" +
            "border:10px solid transparent;border-bottom:18px solid #d32f2f;pointer-events:none}" +
            "#ctr{position:absolute;right:8px;bottom:8px;display:flex;flex-direction:column}" +
            "#ctr button{width:42px;height:42px;margin:3px 0;font-size:20px;border:0;" +
            "border-radius:8px;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.35)}" +
            "#hint{position:absolute;left:8px;bottom:8px;background:rgba(255,255,255,.9);" +
            "padding:5px 9px;border-radius:6px;font-size:11px;color:#444;max-width:60%}" +
            "</style></head><body>" +
            "<div id='map'><svg id='svg'></svg></div>" +
            "<div id='ctr'><button onclick='zoomBy(1)'>+</button>" +
            "<button onclick='zoomBy(-1)'>&minus;</button></div>" +
            "<div id='hint'>点图加点 · 拖点移动 · 长按点删除</div>" +
            "<script>" +
            "var TS=256, MAXZ=19;" +
            // 初始视野只是一个占位坐标（中国境内，方便第一次打开时能加载出地图瓦片）。
            // 真实坐标由 loadPath/selectCenter 从配置里灌进来，见 loadPath()。
            "var cLat=30.000000, cLng=120.000000, z=14;" +
            // vLat/vLng = 当前视野中心，可以和 cLat/cLng（路线中心）不同。
            // 拖地图改的是视野中心，不动路线中心；只有按「定中心」才改路线中心。
            "var vLat=cLat, vLng=cLng;" +
            "var picking=false, path=[], undoStack=[];" +
            "var map=document.getElementById('map'), svg=document.getElementById('svg');" +
            "var tileBox=document.createElement('div');" +
            "tileBox.style.cssText='position:absolute;inset:0';" +
            "map.insertBefore(tileBox, svg);" +
            "var vtxEls=[], ctrEl=null;" +

            "function lng2x(lng,zz){return (lng+180)/360*Math.pow(2,zz)*TS;}" +
            "function lat2y(lat,zz){var r=lat*Math.PI/180;" +
            "return (1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*Math.pow(2,zz)*TS;}" +
            "function x2lng(x,zz){return x/(Math.pow(2,zz)*TS)*360-180;}" +
            "function y2lat(y,zz){var n=Math.PI-2*Math.PI*y/(Math.pow(2,zz)*TS);" +
            "return 180/Math.PI*Math.atan(0.5*(Math.exp(n)-Math.exp(-n)));}" +

            // 屏幕坐标 (px,py) -> 经纬度
            "function screenToLatLng(px,py){" +
            "  var W=map.clientWidth, H=map.clientHeight;" +
            "  var cx=lng2x(vLng,z), cy=lat2y(vLat,z);" +
            "  return [y2lat(cy+(py-H/2),z), x2lng(cx+(px-W/2),z)];" +
            "}" +
            // 经纬度 -> 屏幕坐标
            "function latLngToScreen(lat,lng){" +
            "  var W=map.clientWidth, H=map.clientHeight;" +
            "  var cx=lng2x(vLng,z), cy=lat2y(vLat,z);" +
            "  return [W/2+(lng2x(lng,z)-cx), H/2+(lat2y(lat,z)-cy)];" +
            "}" +

            "var lastKey='';" +
            "function render(){" +
            "  var W=map.clientWidth, H=map.clientHeight;" +
            "  var cx=lng2x(vLng,z), cy=lat2y(vLat,z);" +
            "  var ox=W/2-cx, oy=H/2-cy;" +
            "  var n=Math.pow(2,z);" +
            "  var tx0=Math.floor((cx-W/2)/TS), tx1=Math.floor((cx+W/2)/TS);" +
            "  var ty0=Math.floor((cy-H/2)/TS), ty1=Math.floor((cy+H/2)/TS);" +
            "  var key=z+':'+tx0+','+tx1+','+ty0+','+ty1+':'+Math.round(ox)+','+Math.round(oy);" +
            "  if(key!==lastKey){" +
            "    lastKey=key; tileBox.innerHTML='';" +
            "    for(var tx=tx0;tx<=tx1;tx++){" +
            "      for(var ty=ty0;ty<=ty1;ty++){" +
            "        if(ty<0||ty>=n) continue;" +
            "        var wx=((tx%n)+n)%n;" +
            "        var im=document.createElement('img');" +
            "        im.className='tile';" +
            "        im.src='https://webrd0'+((wx+ty)%4+1)+'.is.autonavi.com/appmaptile" +
            "?lang=zh_cn&size=1&scale=1&style=8&x='+wx+'&y='+ty+'&z='+z;" +
            "        im.style.left=(tx*TS+ox)+'px'; im.style.top=(ty*TS+oy)+'px';" +
            "        tileBox.appendChild(im);" +
            "      }" +
            "    }" +
            "  }" +
            "  drawAll();" +
            "}" +

            // 画中心、连线、顶点
            "function drawAll(){" +
            "  svg.setAttribute('width',map.clientWidth);" +
            "  svg.setAttribute('height',map.clientHeight);" +
            "  var s='';" +
            "  if(path.length>1){" +
            "    s+='<polyline fill=\"rgba(30,136,229,0.12)\" stroke=\"#1e88e5\"'+" +
            "       ' stroke-width=\"3\" points=\"';" +
            "    for(var i=0;i<path.length;i++){" +
            "      var p=latLngToScreen(path[i][0],path[i][1]);" +
            "      s+=p[0]+','+p[1]+' ';" +
            "    }" +
            "    s+='\"/>';" +
            "    var a=latLngToScreen(path[0][0],path[0][1]);" +
            "    var b=latLngToScreen(path[path.length-1][0],path[path.length-1][1]);" +
            "    s+='<line x1=\"'+a[0]+'\" y1=\"'+a[1]+'\" x2=\"'+b[0]+'\" y2=\"'+b[1]+'\"'+" +
            "       ' stroke=\"#1e88e5\" stroke-width=\"2\" stroke-dasharray=\"6,6\" opacity=\"0.6\"/>';" +
            "  }" +
            "  svg.innerHTML=s;" +

            "  for(var i=0;i<vtxEls.length;i++) vtxEls[i].remove();" +
            "  vtxEls=[];" +
            "  for(var i=0;i<path.length;i++){" +
            "    var p=latLngToScreen(path[i][0],path[i][1]);" +
            "    var d=document.createElement('div');" +
            "    d.className='vtx'+(i===0?' first':'');" +
            "    d.textContent=(i+1);" +
            "    d.style.left=p[0]+'px'; d.style.top=p[1]+'px';" +
            "    map.appendChild(d); vtxEls.push(d);" +
            "  }" +
            "  if(ctrEl) ctrEl.remove();" +
            "  var c=latLngToScreen(cLat,cLng);" +
            "  var cm=document.createElement('div'); cm.className='ctrpin';" +
            "  cm.style.left=c[0]+'px'; cm.style.top=c[1]+'px';" +
            "  map.appendChild(cm); ctrEl=cm;" +
            "}" +

            // 命中测试：屏幕坐标附近 22px 内有没有已有点
            "function hitVertex(px,py){" +
            "  for(var i=0;i<path.length;i++){" +
            "    var p=latLngToScreen(path[i][0],path[i][1]);" +
            "    var dx=p[0]-px, dy=p[1]-py;" +
            "    if(dx*dx+dy*dy < 22*22) return i;" +
            "  }" +
            "  return -1;" +
            "}" +

            "function pushUndo(){undoStack.push(JSON.stringify(path));" +
            "  if(undoStack.length>50) undoStack.shift();}" +
            "function undo(){" +
            "  if(!undoStack.length) return;" +
            "  path=JSON.parse(undoStack.pop());" +
            "  lastKey=''; render(); Android.onPath(csv());" +
            "}" +

            // 一键生成跑道：真实田径场是「两条直道 + 两个半圆」，
            // 正圆看着假，也不像在跑道上。
            //
            // 朝向按真实田径场来：**直道沿东西向、两个半圆在南北两端**。
            // （标准 400m 跑道：直道约 84.39m、弯道半径约 36.5m，
            //   这里用「半径」当半圆半径，直道取 2×半径作近似。）
            "function makeRing(){" +
            "  var rEl=null;" +
            "  try{ rEl=Android.radiusM(); }catch(e){}" +
            "  var rm=(rEl&&rEl>0)?rEl:39;" +
            "  var N=24;" +
            "  var latScale=Math.max(0.000001,Math.cos(cLat*Math.PI/180));" +
            "  var dLat1=rm/111320.0;" +                       // 半圆半径 -> 纬度增量
            "  var dLng1=dLat1/latScale;" +                    // 半圆半径 -> 经度增量
            "  var straight=rm*2;" +                           // 直道长度（米）
            "  var halfS=(straight/2)/111320.0/latScale;" +    // 半条直道 -> 经度增量
            "  var arr=[];" +
            "  var per=N/4;" +
            // 直道沿东西：从西直道起点往东走
            "  for(var i=0;i<per;i++){" +                      // 北侧直道（由西向东）
            "    var t=i/per;" +
            "    arr.push([cLat + dLat1, cLng - halfS + (2*halfS)*t]);" +
            "  }" +
            "  for(var i=0;i<per;i++){" +                      // 东侧半圆（北->南）
            "    var a=Math.PI/2 - Math.PI*(i/per);" +
            "    arr.push([cLat + dLat1*Math.sin(a), cLng + halfS + dLng1*Math.cos(a)]);" +
            "  }" +
            "  for(var i=0;i<per;i++){" +                      // 南侧直道（由东向西）
            "    var t=i/per;" +
            "    arr.push([cLat - dLat1, cLng + halfS - (2*halfS)*t]);" +
            "  }" +
            "  for(var i=0;i<per;i++){" +                      // 西侧半圆（南->北）
            "    var a=-Math.PI/2 - Math.PI*(i/per);" +
            "    arr.push([cLat + dLat1*Math.sin(a), cLng - halfS + dLng1*Math.cos(a)]);" +
            "  }" +
            "  pushUndo(); path=arr; lastKey=''; render(); Android.onPath(csv());" +
            "  vLat=cLat; vLng=cLng; lastKey=''; render();" +
            "  Android.onInfo('已生成 '+arr.length+' 点椭圆跑道（半圆半径 '"
            + "+Math.round(rm)+' 米，直道 '+Math.round(straight)+' 米）');" +
            "}" +

            // 交互
            "var drag=null, moved=false, pressTimer=null, holdFired=false, panStart=null;" +
            "map.addEventListener('pointerdown',function(e){" +
            "  var idx=hitVertex(e.clientX,e.clientY);" +
            "  holdFired=false;" +
            "  if(idx>=0){" +
            "    drag={mode:'vertex',idx:idx,x:e.clientX,y:e.clientY};" +
            "    pressTimer=setTimeout(function(){" +
            "      if(!moved){ holdFired=true; pushUndo(); path.splice(drag.idx,1);" +
            "        lastKey=''; render(); Android.onPath(csv()); drag=null; }" +
            "    },600);" +
            "  } else {" +
            "    drag={mode:'pan',x:e.clientX,y:e.clientY,lat:vLat,lng:vLng};" +
            "  }" +
            "  moved=false;" +
            "});" +

            "map.addEventListener('pointermove',function(e){" +
            "  if(!drag) return;" +
            "  var dx=e.clientX-drag.x, dy=e.clientY-drag.y;" +
            "  if(Math.abs(dx)+Math.abs(dy)>6){ moved=true; if(pressTimer){clearTimeout(pressTimer);pressTimer=null;} }" +
            "  if(drag.mode==='pan'){" +
            "    vLng=x2lng(lng2x(drag.lng,z)-dx,z);" +
            "    vLat=y2lat(lat2y(drag.lat,z)-dy,z);" +
            "    render();" +
            "  } else if(drag.mode==='vertex' && moved){" +
            "    var ll=screenToLatLng(e.clientX,e.clientY);" +
            "    path[drag.idx]=ll; lastKey=''; render();" +
            "  }" +
            "});" +

            "map.addEventListener('pointerup',function(e){" +
            "  if(pressTimer){clearTimeout(pressTimer);pressTimer=null;}" +
            "  if(!drag) return;" +
            "  var d=drag; drag=null;" +
            "  if(d.mode==='vertex'){" +
            "    if(moved && !holdFired){ Android.onPath(csv()); }" +
            "    return;" +
            "  }" +
            "  if(moved) return;" +   // 拖动地图，不是点击
            "  var ll=screenToLatLng(e.clientX,e.clientY);" +
            "  if(picking){ cLat=ll[0]; cLng=ll[1]; vLat=ll[0]; vLng=ll[1]; Android.onCenter(ll[0],ll[1]); }" +
            "  else { pushUndo(); path.push(ll); Android.onPath(csv()); }" +
            "  lastKey=''; render();" +
            "});" +

            "function csv(){var a=[];for(var i=0;i<path.length;i++)" +
            "a.push(path[i][0]+','+path[i][1]);return a.join(';');}" +
            "function setPickMode(v){picking=!!v;}" +
            "function clearPath(){pushUndo();path=[];lastKey='';render();Android.onPath('');}" +
            "function zoomBy(d){" +
            "  var nz=Math.max(13,Math.min(MAXZ,z+d));" +
            "  if(nz===z) return; z=nz; lastKey=''; render();" +
            "}" +
            "function loadPath(csvStr,lat,lng){" +
            "  path=[]; undoStack=[];" +
            "  if(csvStr){var segs=csvStr.split(';');" +
            "    for(var i=0;i<segs.length;i++){var xy=segs[i].split(',');" +
            "      if(xy.length===2 && xy[0] && xy[1])" +
            "        path.push([parseFloat(xy[0]),parseFloat(xy[1])]);}}" +
            // centerLat/centerLng 为 0 表示「还没设过」（代码默认值），
            // 此时用路径首点当视野中心，免得每次打开都落在占位坐标上。
            "  if(lat||lng){ cLat=lat; vLat=lat; cLng=lng; vLng=lng; }" +
            "  else if(path.length){ cLat=path[0][0]; vLat=cLat; cLng=path[0][1]; vLng=cLng;" +
            "    z=Math.max(13,Math.min(MAXZ,17)); }" +
            "  lastKey=''; render();" +
            "}" +
            "window.addEventListener('resize',function(){lastKey='';render();});" +
            "render();" +
            "</script></body></html>";
}
