package com.foodpicker.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动采集菜单（边采边识别）。
 * <p>
 * 用户进入一家店后点悬浮的「采集本店」：服务逐屏下滑，每屏优先读无障碍树里的文字
 * （小程序页面通常能读到，又小又快），读不到才截图。每攒够一批就丢给后台线程
 * 发给 AI 识别，结果追加写入文件。滑到底后自动返回店铺列表，等用户进下一家。
 * 点「完成」后等识别收尾，回到 App 一键导入。
 * <p>
 * 「自动逐店」：在店铺列表页读出店名（店名后面紧跟「销量 xxx」），逐个点进去采集，
 * 采完返回、记下已采过的，当前屏采完就往下滑，直到列表到底。
 * 只会点店铺名，不会点加购、下单之类的按钮。
 */
public class AutoCollectService extends AccessibilityService {

    static volatile AutoCollectService instance;
    /** App 里点了「开始」后为 true，服务连上就显示悬浮面板 */
    static volatile boolean armed;
    /** 一次采集会话进行中（从开始到「完成」）；为 false 时结果文件可以交给 App */
    static volatile boolean sessionActive;
    /** 网页传来的 AI 配置和提示词 */
    static volatile String configJson;

    static final String RESULT_FILE = "collect_result.txt";

    private static final int MAX_SCREENS_PER_SHOP = 120;
    private static final int TEXT_LINES_PER_REQ = 160;
    private static final int MAX_INFLIGHT = 4;
    private static final Pattern PRICE = Pattern.compile("(?:[¥￥]\\s*\\d{1,4}(?:\\.\\d{1,2})?)|(?:^\\d{1,4}(?:\\.\\d{1,2})?\\s*元?$)");
    private static final Pattern SHOP = Pattern.compile("【(.+?)】");
    private static final Pattern SALES = Pattern.compile("(月售|销量|已售)\\s*\\d+");
    /** 整张店铺卡片被读成一段时（「润园三楼至尊披萨 4.6 销量1022 …」），取评分/销量前面的部分当店名 */
    private static final Pattern BADGE = Pattern.compile("(\\s+(外卖|自取|堂食|到店|自提|新店|品牌))+$");
    private static final Pattern CARD_NAME = Pattern.compile("^(.{2,30}?)\\s+(?:[★☆]?\\s*\\d\\.\\d|月售|销量|已售)");
    private static final Pattern RATING = Pattern.compile("^[★☆]?\\s*\\d(\\.\\d)?\\s*分?$");

    /** 屏幕上的一段文字和它的位置 */
    static final class TextNode {
        final String text;
        final Rect rect;

        TextNode(String text, Rect rect) {
            this.text = text;
            this.rect = rect;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private WindowManager wm;
    private LinearLayout panel;
    private TextView mainBtn, autoBtn, doneBtn;

    private JSONObject cfg;
    private int perReq = 4;

    // 会话统计（主线程读写）
    private int shopsDone, dishes, failures, inflight;
    private String lastError;
    /** 显示在面板上的提示（很多手机会屏蔽后台 App 的 Toast） */
    private String notice;
    private boolean finishing;

    // 当前店铺（主线程读写）
    private boolean running;
    private int shopScreens, stillCount;
    private boolean usedImages;
    private List<String> prevLines = new ArrayList<>();
    private final List<String> pendingLines = new ArrayList<>();
    private final List<String> pendingImages = new ArrayList<>();
    private List<String> tailContext = new ArrayList<>();
    private int[] lastThumb;
    private Future<String> shopFirst;   // 本店第一批的识别结果，用来拿店铺名

    // 自动逐店（主线程读写）
    private boolean auto;
    private final Set<String> visited = new HashSet<>();
    private int listStill, enterWaits;
    private List<String> beforeTap = new ArrayList<>();

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    static boolean isEnabled(Context ctx) {
        String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(ctx.getPackageName() + "/");
    }

    static void arm(String config) {
        configJson = config;
        armed = true;
        AutoCollectService s = instance;
        if (s != null) s.main.post(s::showPanel);
    }

    static File resultFile(Context ctx) {
        return new File(ctx.getFilesDir(), RESULT_FILE);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        if (armed) showPanel();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        hidePanel();
        instance = null;
        return super.onUnbind(intent);
    }

    // ---------- 悬浮面板 ----------

    @SuppressLint({"ClickableViewAccessibility", "RtlHardcoded", "SetTextI18n"})
    private void showPanel() {
        if (panel != null) return;
        try {
            cfg = new JSONObject(configJson == null ? "{}" : configJson);
        } catch (Exception e) {
            cfg = new JSONObject();
        }
        perReq = Math.max(1, cfg.optInt("perReq", 4));
        // 新会话：清空旧结果
        shopsDone = dishes = failures = inflight = 0;
        lastError = null;
        finishing = false;
        sessionActive = true;
        //noinspection ResultOfMethodCallIgnored
        resultFile(this).delete();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        final float dp = getResources().getDisplayMetrics().density;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE2B2118);
        bg.setCornerRadius(22 * dp);
        panel.setBackground(bg);
        panel.setElevation(6 * dp);
        panel.setPadding((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));

        mainBtn = pill(0xFFFF6B35, dp);
        mainBtn.setMaxWidth((int) (280 * dp));
        autoBtn = pill(0x33FFFFFF, dp);
        doneBtn = pill(0x33FFFFFF, dp);
        doneBtn.setText("完成");
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap2.leftMargin = (int) (4 * dp);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(autoBtn);
        row.addView(doneBtn, gap2);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (4 * dp);
        panel.addView(mainBtn);
        panel.addView(row, rowLp);
        updateUi();

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = (int) (12 * dp);
        lp.y = (int) (140 * dp);

        View.OnTouchListener drag = new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) + Math.abs(dy) > 12 * dp) moved = true;
                        if (moved && panel != null) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            wm.updateViewLayout(panel, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (v == doneBtn) onDone();
                            else if (v == autoBtn) onAuto();
                            else onMain();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        };
        mainBtn.setOnTouchListener(drag);
        autoBtn.setOnTouchListener(drag);
        doneBtn.setOnTouchListener(drag);
        wm.addView(panel, lp);
        Toast.makeText(this, "在店铺列表页点「自动逐店」全自动采集；或进入一家店后点「采集本店」", Toast.LENGTH_LONG).show();
    }

    private TextView pill(int color, float dp) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setPadding((int) (14 * dp), (int) (9 * dp), (int) (14 * dp), (int) (9 * dp));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(18 * dp);
        t.setBackground(g);
        return t;
    }

    private void hidePanel() {
        if (panel != null && wm != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
        }
        panel = null;
        mainBtn = autoBtn = doneBtn = null;
    }

    @SuppressLint("SetTextI18n")
    private void updateUi() {
        if (mainBtn == null) return;
        String stats = "已采 " + shopsDone + " 家 · 识别 " + dishes + " 道" + (inflight > 0 ? " · 识别中 " + inflight : "")
                + (failures > 0 ? " · 失败 " + failures : "");
        if (finishing) {
            mainBtn.setText("等待识别收尾… " + stats);
        } else if (running) {
            mainBtn.setText("■ 本店 " + shopScreens + " 屏 · " + stats);
        } else if (auto) {
            mainBtn.setText("找下一家店… " + stats);
        } else {
            mainBtn.setText("▶ 采集本店（" + stats + "）" + (notice != null ? "\n⚠ " + notice : ""));
        }
        autoBtn.setText(auto ? "停止自动" : "自动逐店");
        autoBtn.setVisibility(finishing ? View.GONE : View.VISIBLE);
        doneBtn.setVisibility(finishing ? View.GONE : View.VISIBLE);
    }

    private void onMain() {
        if (finishing || auto) return;
        notice = null;
        if (running) {
            endShop(false);
            return;
        }
        startShop();
    }

    private void startShop() {
        if (!isSupported()) {
            Toast.makeText(this, "自动采集需要 Android 11 及以上", Toast.LENGTH_LONG).show();
            return;
        }
        running = true;
        shopScreens = stillCount = 0;
        usedImages = false;
        prevLines = new ArrayList<>();
        tailContext = new ArrayList<>();
        pendingLines.clear();
        pendingImages.clear();
        lastThumb = null;
        shopFirst = null;
        updateUi();
        main.postDelayed(this::step, 300);
    }

    private void onAuto() {
        if (finishing) return;
        if (auto) {
            auto = false;
            if (running) endShop(false);
            updateUi();
            return;
        }
        if (running) return;
        auto = true;
        listStill = 0;
        notice = null;
        updateUi();
        // 稍等一下再读屏：刚点完悬浮面板时，系统认为「活动窗口」是面板本身
        main.postDelayed(this::listStep, 600);
    }

    private void onDone() {
        if (finishing) return;
        auto = false;
        if (running) endShop(false);
        finishing = true;
        updateUi();
        waitAndReturn();
    }

    // ---------- 逐屏循环 ----------

    private void step() {
        if (!running || panel == null) return;
        if (inflight >= MAX_INFLIGHT) {   // AI 跟不上时先等等，避免积压太多
            updateUi();
            main.postDelayed(this::step, 500);
            return;
        }
        List<String> lines = readScreenText();
        if (countPrices(lines) >= 2) {
            onText(lines);
        } else if (isSupported() && !auto) {
            capture();
        } else {
            endShop(true);
        }
    }

    /** 文字模式：只取和上一屏不重叠的新行 */
    private void onText(List<String> lines) {
        List<String> fresh = newLines(prevLines, lines);
        prevLines = lines;
        if (fresh.isEmpty()) {
            if (++stillCount >= 2) {
                endShop(true);
                return;
            }
        } else {
            stillCount = 0;
            shopScreens++;
            pendingLines.addAll(fresh);
            if (pendingLines.size() >= TEXT_LINES_PER_REQ) flushText();
        }
        next();
    }

    @TargetApi(30)
    private void capture() {
        if (panel != null) panel.setAlpha(0f);   // 截图时藏起面板
        main.postDelayed(() -> {
            if (!running) return;
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    if (panel != null) panel.setAlpha(1f);
                    onShot(result);
                }

                @Override
                public void onFailure(int errorCode) {
                    if (panel != null) panel.setAlpha(1f);
                    if (running) main.postDelayed(AutoCollectService.this::step, 600);
                }
            });
        }, 150);
    }

    @TargetApi(30)
    private void onShot(ScreenshotResult result) {
        if (!running) return;
        HardwareBuffer hb = result.getHardwareBuffer();
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
        if (hw == null) {
            hb.close();
            endShop(true);
            return;
        }
        Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        hb.close();
        int[] thumb = thumbOf(bmp);
        boolean same = lastThumb != null && diff(thumb, lastThumb) < 3;
        lastThumb = thumb;
        if (same) {
            bmp.recycle();
            if (++stillCount >= 2) {
                endShop(true);
                return;
            }
        } else {
            stillCount = 0;
            shopScreens++;
            usedImages = true;
            pendingImages.add(encode(bmp));
            bmp.recycle();
            if (pendingImages.size() >= perReq) flushImages();
        }
        next();
    }

    private void next() {
        updateUi();
        if (shopScreens >= MAX_SCREENS_PER_SHOP) {
            endShop(true);
            return;
        }
        swipeUp();
    }

    /** 本店结束：把剩下的交给 AI；滑到底的话自动返回店铺列表 */
    private void endShop(boolean reachedEnd) {
        if (!running) return;
        running = false;
        flushText();
        flushImages();
        if (shopScreens > 0) shopsDone++;
        updateUi();
        if (auto) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            main.postDelayed(this::listStep, 1500);
        } else if (reachedEnd && shopScreens > 0) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            notice = "本店采完已返回，进下一家后再点我";
            updateUi();
        }
    }

    // ---------- 自动逐店 ----------

    /** 在店铺列表页找一家还没采过的店点进去；当前屏都采过就往下滑 */
    private void listStep() {
        if (!auto || panel == null || finishing || running) return;
        if (inflight >= MAX_INFLIGHT) {
            updateUi();
            main.postDelayed(this::listStep, 800);
            return;
        }
        List<TextNode> nodes = readNodes();
        if (nodes.isEmpty()) {
            stopAuto("读不到页面文字（微信没有开放这个页面的文字），只能手动进店后点「采集本店」，会改用截图");
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int top = (int) (dm.heightPixels * 0.12), bottom = (int) (dm.heightPixels * 0.88);
        boolean sawShop = false;
        for (TextNode n : shopNames(nodes)) {
            sawShop = true;
            if (visited.contains(n.text) || n.rect.centerY() < top || n.rect.centerY() > bottom) continue;
            visited.add(n.text);
            listStill = 0;
            enterWaits = 0;
            beforeTap = texts(nodes);
            tap(n.rect.centerX(), n.rect.centerY());
            main.postDelayed(this::checkEntered, 1500);
            return;
        }
        if (!sawShop && visited.isEmpty()) {
            if (++listStill <= 2) {   // 店铺列表在首页下方，先往下滑几屏找找
                swipeList();
                return;
            }
            copyDebug(nodes);
            stopAuto("读到 " + nodes.size() + " 段文字，但没认出店铺。已把读到的文字复制到剪贴板，可以发给开发者");
            return;
        }
        if (++listStill > 3) {   // 连续几屏都没有新店：列表到底了
            auto = false;
            Toast.makeText(this, "店铺列表采完了，共 " + visited.size() + " 家", Toast.LENGTH_LONG).show();
            onDone();
            return;
        }
        swipeList();
    }

    /** 点店名后：等页面加载出价格再开始采集；没跳转或不是店铺页就跳过 */
    private void checkEntered() {
        if (!auto || panel == null) return;
        List<String> lines = readScreenText();
        if (countPrices(lines) >= 2 && !lines.equals(beforeTap)) {
            startShop();
            return;
        }
        if (++enterWaits <= 4) {   // 店铺页先出灰色骨架屏，多等一会儿
            main.postDelayed(this::checkEntered, 800);
            return;
        }
        if (!lines.equals(beforeTap)) performGlobalAction(GLOBAL_ACTION_BACK);   // 进错页面了，退回去
        main.postDelayed(this::listStep, 1200);
    }

    private void stopAuto(String msg) {
        auto = false;
        notice = msg;
        updateUi();
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** 自动逐店认不出店铺时，把读到的文字放进剪贴板，方便发给开发者看 */
    private void copyDebug(List<TextNode> nodes) {
        StringBuilder sb = new StringBuilder("【自动逐店调试】读到 ").append(nodes.size()).append(" 段文字：\n");
        for (int i = 0; i < nodes.size() && i < 200; i++) sb.append(nodes.get(i).text).append('\n');
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("debug", sb.toString()));
        } catch (Exception ignored) {
        }
    }

    /**
     * 店名的特征：后面 1~3 段文字内有「销量 123 / 月售 123」，中间可能隔着评分、「外卖」之类的小标签。
     * 在这几段里跳过评分和价格，取最长的那段当店名。
     */
    static List<TextNode> shopNames(List<TextNode> nodes) {
        List<TextNode> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            String cur = nodes.get(i).text;
            if (!SALES.matcher(cur).find()) continue;
            Matcher card = CARD_NAME.matcher(cur);
            if (card.find()) {   // 整张卡片是一段文字
                String name = BADGE.matcher(card.group(1).trim()).replaceAll("").trim();
                if (seen.add(name)) out.add(new TextNode(name, nodes.get(i).rect));
                continue;
            }
            TextNode best = null;
            Rect sr = nodes.get(i).rect;
            int maxGap = Math.max(200, sr.height() * 5);   // 店名就在销量上面一两行
            for (int j = i - 1; j >= Math.max(0, i - 3); j--) {
                String t = nodes.get(j).text;
                if (RATING.matcher(t).matches() || SALES.matcher(t).find() || PRICE.matcher(t).find()) continue;
                if (sr.top - nodes.get(j).rect.top > maxGap) continue;
                if (best == null || t.length() > best.text.length()) best = nodes.get(j);
            }
            if (best != null && best.text.length() >= 2 && seen.add(best.text)) out.add(best);
        }
        return out;
    }

    private static List<String> texts(List<TextNode> nodes) {
        List<String> out = new ArrayList<>();
        for (TextNode n : nodes) out.add(n.text);
        return out;
    }

    // ---------- 识别队列 ----------

    private void flushText() {
        if (pendingLines.isEmpty()) return;
        // 带上上一批的最后几行，避免菜名和价格被拆到两批里
        List<String> chunk = new ArrayList<>(tailContext);
        chunk.addAll(pendingLines);
        tailContext = new ArrayList<>(pendingLines.subList(Math.max(0, pendingLines.size() - 8), pendingLines.size()));
        pendingLines.clear();
        submit(Collections.emptyList(), String.join("\n", chunk));
    }

    private void flushImages() {
        if (pendingImages.isEmpty()) return;
        List<String> batch = new ArrayList<>(pendingImages);
        pendingImages.clear();
        submit(batch, null);
    }

    private void submit(final List<String> images, final String text) {
        final Future<String> first = shopFirst;
        final boolean isFirst = first == null;
        inflight++;
        updateUi();
        Future<String> f = pool.submit(() -> {
            String out = null;
            try {
                String hint = "";
                if (!isFirst) {
                    String firstOut = "";
                    try {
                        firstOut = first.get();
                    } catch (Exception ignored) {
                    }
                    Matcher m = SHOP.matcher(firstOut == null ? "" : firstOut);
                    if (m.find() && !"未命名店铺".equals(m.group(1))) {
                        hint = "这些内容和前面的来自同一家店的同一份菜单，店铺名统一写【" + m.group(1) + "】。\n\n";
                    }
                }
                String prompt = text == null
                        ? hint + cfg.optString("imagePrompt")
                        : hint + cfg.optString("textPrompt") + "\n\n页面文字：\n" + text;
                out = Ai.ask(cfg, images, prompt);
                appendResult(out);
                final int n = countDishes(out);
                main.post(() -> {
                    dishes += n;
                    inflight--;
                    updateUi();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                main.post(() -> {
                    failures++;
                    inflight--;
                    lastError = msg;
                    updateUi();
                    if (failures == 1) Toast.makeText(this, "识别失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
            return out == null ? "" : out;
        });
        if (isFirst) shopFirst = f;
    }

    private synchronized void appendResult(String text) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(resultFile(this), true)) {
            fos.write((text.trim() + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private void waitAndReturn() {
        if (inflight > 0) {
            updateUi();
            main.postDelayed(this::waitAndReturn, 500);
            return;
        }
        armed = false;
        sessionActive = false;
        hidePanel();
        if (dishes == 0) {
            Toast.makeText(this, lastError != null ? "没有识别到菜品：" + lastError : "没有识别到菜品", Toast.LENGTH_LONG).show();
        }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(MainActivity.EXTRA_COLLECTED, true);
        startActivity(i);
    }

    // ---------- 读屏 ----------

    private List<String> readScreenText() {
        return texts(readNodes());
    }

    /**
     * 读当前屏幕上别的 App 的文字。不能只用 getRootInActiveWindow()：刚点过悬浮面板时，
     * 「活动窗口」是面板自己。所以遍历所有窗口，跳过本 App，取最上层有文字的应用窗口。
     */
    private List<TextNode> readNodes() {
        List<TextNode> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || isOurs(root)) continue;
                collect(root, out, 0);
                if (!out.isEmpty()) return out;
            }
        } catch (Exception ignored) {
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && !isOurs(root)) collect(root, out, 0);
        return out;
    }

    private boolean isOurs(AccessibilityNodeInfo root) {
        return getPackageName().contentEquals(String.valueOf(root.getPackageName()));
    }

    private static void collect(AccessibilityNodeInfo node, List<TextNode> out, int depth) {
        if (node == null || depth > 80) return;
        if (node.isVisibleToUser()) {
            CharSequence t = node.getText();
            if (t == null || t.length() == 0) t = node.getContentDescription();
            if (t != null) {
                String s = t.toString().replaceAll("\\s+", " ").trim();
                if (!s.isEmpty() && s.length() <= 80) {
                    Rect r = new Rect();
                    node.getBoundsInScreen(r);
                    out.add(new TextNode(s, r));
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), out, depth + 1);
    }

    private static int countPrices(List<String> lines) {
        int n = 0;
        for (String s : lines) if (PRICE.matcher(s).find()) n++;
        return n;
    }

    /** 找出 cur 里上一屏没有的部分：定位两屏最长的连续相同片段，取它之后的行 */
    static List<String> newLines(List<String> prev, List<String> cur) {
        if (prev.isEmpty()) return new ArrayList<>(cur);
        int best = 0, bestEnd = -1;
        int[][] dp = new int[prev.size() + 1][cur.size() + 1];
        for (int i = 1; i <= prev.size(); i++) {
            for (int j = 1; j <= cur.size(); j++) {
                if (prev.get(i - 1).equals(cur.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > best) {
                        best = dp[i][j];
                        bestEnd = j - 1;
                    }
                }
            }
        }
        if (best < 3) return new ArrayList<>(cur);
        return new ArrayList<>(cur.subList(bestEnd + 1, cur.size()));
    }

    private static int countDishes(String out) {
        int n = 0;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("【") && s.matches(".*\\d.*")) n++;
        }
        return n;
    }

    // ---------- 手势 ----------

    /** 在屏幕右侧（菜品列表一般在右边）往上滑约 45% 屏高，结尾停住不松手，避免惯性滑过头 */
    @TargetApi(30)
    private void swipeUp() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        swipe(dm.widthPixels * 0.68f, dm.heightPixels * 0.75f, dm.heightPixels * 0.30f, this::step, usedImages ? 650 : 450);
    }

    /** 店铺列表：在屏幕中间往上滑半屏 */
    @TargetApi(30)
    private void swipeList() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        swipe(dm.widthPixels * 0.5f, dm.heightPixels * 0.78f, dm.heightPixels * 0.30f, this::listStep, 900);
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    @TargetApi(30)
    private void swipe(float x, float y0, float y1, final Runnable then, final int settleMs) {
        Path move = new Path();
        move.moveTo(x, y0);
        move.lineTo(x, y1);
        Path hold = new Path();
        hold.moveTo(x, y1);
        hold.lineTo(x, y1 + 1);
        GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(move, 0, 450, true);
        GestureDescription.StrokeDescription s2 = s1.continueStroke(hold, 0, 200, false);
        final GestureDescription second = new GestureDescription.Builder().addStroke(s2).build();
        final GestureResultCallback after = new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                main.postDelayed(then, settleMs);
            }

            @Override
            public void onCancelled(GestureDescription g) {
                main.postDelayed(then, settleMs + 200);
            }
        };
        dispatchGesture(new GestureDescription.Builder().addStroke(s1).build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                dispatchGesture(second, after, null);
            }

            @Override
            public void onCancelled(GestureDescription g) {
                after.onCancelled(g);
            }
        }, null);
    }

    // ---------- 图片工具 ----------

    private static String encode(Bitmap bmp) {
        Bitmap out = bmp;
        if (bmp.getWidth() > 720) {
            out = Bitmap.createScaledBitmap(bmp, 720, bmp.getHeight() * 720 / bmp.getWidth(), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 75, bos);
        if (out != bmp) out.recycle();
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    /** 缩成 24×48 灰度图，用来判断两次截图是否一样 */
    private static int[] thumbOf(Bitmap bmp) {
        Bitmap t = Bitmap.createScaledBitmap(bmp, 24, 48, true);
        int[] px = new int[24 * 48];
        t.getPixels(px, 0, 24, 0, 0, 24, 48);
        t.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
        }
        return px;
    }

    private static int diff(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return (int) (sum / a.length);
    }
}
