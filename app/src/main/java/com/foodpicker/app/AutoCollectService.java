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
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动采集菜单：在微信小程序等页面上显示一个悬浮按钮，点开始后
 * 「截图 → 向上滑一屏 → 再截图」，直到画面不再变化（到底了），
 * 然后带着截图回到 App 交给 AI 识别。只读屏幕和滑动，不点击任何东西。
 */
public class AutoCollectService extends AccessibilityService {

    static volatile AutoCollectService instance;
    /** App 里点了「开始」后为 true，服务连上就显示悬浮按钮 */
    static volatile boolean armed;
    /** 采集结果（data URL），由 MainActivity 取走 */
    static final List<String> collected = new ArrayList<>();

    private static final int MAX_SHOTS = 30;
    private static final int OUT_WIDTH = 720;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView button;
    private boolean running;
    private int[] lastThumb;
    private final List<String> shots = new ArrayList<>();

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    static boolean isEnabled(Context ctx) {
        String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(ctx.getPackageName() + "/");
    }

    static void arm() {
        armed = true;
        AutoCollectService s = instance;
        if (s != null) s.main.post(s::showButton);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        if (armed) showButton();
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
        hideButton();
        instance = null;
        return super.onUnbind(intent);
    }

    // ---------- 悬浮按钮 ----------

    @SuppressLint({"ClickableViewAccessibility", "RtlHardcoded"})
    private void showButton() {
        if (button != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        float dp = getResources().getDisplayMetrics().density;
        button = new TextView(this);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (10 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEEFF6B35);
        bg.setCornerRadius(24 * dp);
        button.setBackground(bg);
        button.setElevation(6 * dp);
        setIdleText();

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = (int) (12 * dp);
        lp.y = (int) (160 * dp);

        // 可拖动；移动距离很小才算点击；长按关闭
        button.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            long downAt;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        downAt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) + Math.abs(dy) > 12 * dp) moved = true;
                        if (moved) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            wm.updateViewLayout(button, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (!running && System.currentTimeMillis() - downAt > 600) close();
                            else onButtonClick();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(button, lp);
        Toast.makeText(this, "打开要采集的菜单页，点悬浮按钮「开始采集」；长按按钮可关闭", Toast.LENGTH_LONG).show();
    }

    private void hideButton() {
        if (button != null && wm != null) {
            try {
                wm.removeView(button);
            } catch (Exception ignored) {
            }
        }
        button = null;
    }

    private void close() {
        armed = false;
        running = false;
        hideButton();
    }

    private void setIdleText() {
        button.setText("▶ 开始采集");
    }

    private void onButtonClick() {
        if (running) {
            finish();
        } else if (!isSupported()) {
            Toast.makeText(this, "自动采集需要 Android 11 及以上", Toast.LENGTH_LONG).show();
        } else {
            running = true;
            shots.clear();
            lastThumb = null;
            button.setText("■ 准备中…");
            main.postDelayed(this::step, 400);
        }
    }

    // ---------- 截图 + 滑动循环 ----------

    @TargetApi(30)
    private void step() {
        if (!running || button == null) return;
        button.setAlpha(0f);   // 截图时把按钮藏起来，免得挡住菜品
        main.postDelayed(() -> {
            if (!running) return;
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    if (button != null) button.setAlpha(1f);
                    handleShot(result);
                }

                @Override
                public void onFailure(int errorCode) {
                    if (button != null) button.setAlpha(1f);
                    // 截图有频率限制，稍等再试
                    if (running) main.postDelayed(AutoCollectService.this::step, 600);
                }
            });
        }, 150);
    }

    @TargetApi(30)
    private void handleShot(ScreenshotResult result) {
        if (!running) return;
        HardwareBuffer hb = result.getHardwareBuffer();
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
        if (hw == null) {
            hb.close();
            finish();
            return;
        }
        Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        hb.close();

        int[] thumb = thumbOf(bmp);
        if (lastThumb != null && diff(thumb, lastThumb) < 3) {
            // 和上一张几乎一样：滑不动了，说明到底了
            bmp.recycle();
            finish();
            return;
        }
        lastThumb = thumb;
        shots.add(encode(bmp));
        bmp.recycle();
        if (button != null) button.setText("■ 已截 " + shots.size() + " 张，点我完成");
        if (shots.size() >= MAX_SHOTS) {
            finish();
            return;
        }
        swipeUp();
    }

    /** 在屏幕右侧（菜品列表一般在右边）往上滑约 40% 屏高，结尾停住不松手，避免惯性滑过头 */
    @TargetApi(30)
    private void swipeUp() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.68f;
        float y0 = dm.heightPixels * 0.72f, y1 = dm.heightPixels * 0.32f;
        Path move = new Path();
        move.moveTo(x, y0);
        move.lineTo(x, y1);
        Path hold = new Path();
        hold.moveTo(x, y1);
        hold.lineTo(x, y1 + 1);
        GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(move, 0, 500, true);
        GestureDescription.StrokeDescription s2 = s1.continueStroke(hold, 0, 250, false);
        GestureDescription first = new GestureDescription.Builder().addStroke(s1).build();
        final GestureDescription second = new GestureDescription.Builder().addStroke(s2).build();
        final GestureResultCallback next = new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                main.postDelayed(AutoCollectService.this::step, 700);
            }

            @Override
            public void onCancelled(GestureDescription g) {
                main.postDelayed(AutoCollectService.this::step, 700);
            }
        };
        dispatchGesture(first, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                dispatchGesture(second, next, null);
            }

            @Override
            public void onCancelled(GestureDescription g) {
                next.onCancelled(g);
            }
        }, null);
    }

    private void finish() {
        running = false;
        List<String> result = new ArrayList<>(shots);
        shots.clear();
        close();
        if (result.isEmpty()) {
            Toast.makeText(this, "没有采集到截图", Toast.LENGTH_SHORT).show();
            return;
        }
        synchronized (collected) {
            collected.clear();
            collected.addAll(result);
        }
        Toast.makeText(this, "采集完成，共 " + result.size() + " 张，正在返回识别", Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(MainActivity.EXTRA_COLLECTED, true);
        startActivity(i);
    }

    // ---------- 图片工具 ----------

    private static String encode(Bitmap bmp) {
        Bitmap out = bmp;
        if (bmp.getWidth() > OUT_WIDTH) {
            out = Bitmap.createScaledBitmap(bmp, OUT_WIDTH, bmp.getHeight() * OUT_WIDTH / bmp.getWidth(), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 80, bos);
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
