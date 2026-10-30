/* Copyright 2026 SecondScreen S20 Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.secondscreen.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.farmerbb.secondscreen.util.U;

import java.util.ArrayList;
import java.util.List;

public class AutoSkipService extends AccessibilityService {
    private static final String TAG = "AutoSkipService";

    private static String sLastSkippedPackage = "";
    private static long sLastSkippedTime = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "AutoSkipService connected and active");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        SharedPreferences pref = U.getPrefMain(this);
        if (!pref.getBoolean("auto_skip_ads", true)) {
            return;
        }

        CharSequence pkgChars = event.getPackageName();
        if (pkgChars == null) return;
        String pkg = pkgChars.toString();

        if (pkg.startsWith("com.farmerbb.secondscreen")
                || pkg.equals("android")
                || pkg.equals("com.android.systemui")
                || pkg.equals("com.sec.android.app.launcher")) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (pkg.equals(sLastSkippedPackage) && (now - sLastSkippedTime < 2500)) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            root = event.getSource();
        }
        if (root == null) return;

        try {
            if (tryFindAndClickSkip(root, pkg)) {
                sLastSkippedPackage = pkg;
                sLastSkippedTime = now;
            }
        } finally {
            try {
                root.recycle();
            } catch (Throwable ignored) {}
        }
    }

    private boolean tryFindAndClickSkip(AccessibilityNodeInfo node, String pkg) {
        if (node == null) return false;

        if (isSkipNode(node)) {
            if (performSkipClick(node)) {
                Log.i(TAG, "Auto-skipped splash ad in: " + pkg);
                return true;
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean clicked = tryFindAndClickSkip(child, pkg);
                try {
                    child.recycle();
                } catch (Throwable ignored) {}
                if (clicked) return true;
            }
        }

        return false;
    }

    private boolean isSkipNode(AccessibilityNodeInfo node) {
        if (isSkipText(node.getText())) return true;
        if (isSkipText(node.getContentDescription())) return true;
        if (isSkipId(node.getViewIdResourceName())) return true;
        return false;
    }

    private static boolean isSkipText(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        if (s.isEmpty() || s.length() > 15) return false;
        if (s.contains("不跳过") || s.contains("不能跳过")) return false;

        if (s.contains("跳过")) return true;
        if (s.contains("关闭广告")) return true;

        String lower = s.toLowerCase();
        if (lower.equals("skip") || lower.startsWith("skip ") || lower.contains("skip ad") || lower.contains("skipad")) {
            return true;
        }

        return false;
    }

    private static boolean isSkipId(String resId) {
        if (resId == null) return false;
        String lower = resId.toLowerCase();
        if (lower.contains("container") || lower.contains("layout") || lower.contains("wrapper")) {
            return false;
        }
        return lower.contains("splash_skip")
                || lower.contains("skip_button")
                || lower.contains("btn_skip")
                || lower.contains("skip_view")
                || lower.contains("skip_tv")
                || lower.contains("tv_skip")
                || lower.contains("count_down")
                || lower.contains("splash_ad_close");
    }

    private boolean performSkipClick(AccessibilityNodeInfo target) {
        if (target == null) return false;

        Rect bounds = new Rect();
        target.getBoundsInScreen(bounds);

        // 1. Direct node action click
        if (target.isClickable()) {
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }

        // 2. Parent clickable check
        AccessibilityNodeInfo parent = target.getParent();
        int depth = 0;
        while (parent != null && depth < 4) {
            if (parent.isClickable()) {
                boolean clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                try {
                    parent.recycle();
                } catch (Throwable ignored) {}
                if (clicked) return true;
            }
            AccessibilityNodeInfo prev = parent;
            parent = parent.getParent();
            try {
                prev.recycle();
            } catch (Throwable ignored) {}
            depth++;
        }

        // 3. Gesture tap fallback (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && bounds.width() > 0 && bounds.height() > 0) {
            Path path = new Path();
            path.moveTo(bounds.centerX(), bounds.centerY());
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 50);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
            return true;
        }

        return false;
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AutoSkipService interrupted");
    }

    /**
     * Checks if AutoSkipService is enabled in Settings.Secure; if not, enables it via root shell commands.
     */
    public static void checkAndEnable(final Context context) {
        if (context == null) return;
        new Thread(() -> {
            try {
                SharedPreferences pref = U.getPrefMain(context);
                if (!pref.getBoolean("auto_skip_ads", true)) {
                    return;
                }

                ContentResolver cr = context.getContentResolver();
                String currentServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                String serviceName = context.getPackageName() + "/" + AutoSkipService.class.getName();

                boolean contains = currentServices != null && currentServices.contains(serviceName);
                int enabled = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);

                if (!contains || enabled == 0) {
                    String newServices;
                    if (currentServices == null || currentServices.trim().isEmpty() || "null".equalsIgnoreCase(currentServices.trim())) {
                        newServices = serviceName;
                    } else if (!contains) {
                        newServices = currentServices + ":" + serviceName;
                    } else {
                        newServices = currentServices;
                    }

                    try {
                        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices);
                        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    } catch (Throwable ignored) {}

                    String cmd1 = "settings put secure enabled_accessibility_services " + newServices;
                    String cmd2 = "settings put secure accessibility_enabled 1";
                    U.runCommands(context, new String[]{cmd1, cmd2}, false);
                    Log.i(TAG, "Enabled AutoSkipService via root command");
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in checkAndEnable", t);
            }
        }).start();
    }

    /**
     * Disables AutoSkipService if it is currently enabled.
     */
    public static void disable(final Context context) {
        if (context == null) return;
        new Thread(() -> {
            try {
                ContentResolver cr = context.getContentResolver();
                String currentServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                String serviceName = context.getPackageName() + "/" + AutoSkipService.class.getName();

                if (currentServices != null && currentServices.contains(serviceName)) {
                    List<String> list = new ArrayList<>();
                    for (String s : currentServices.split(":")) {
                        if (!s.equals(serviceName) && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim())) {
                            list.add(s);
                        }
                    }
                    String newServices = TextUtils.join(":", list);

                    try {
                        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices.isEmpty() ? null : newServices);
                        if (list.isEmpty()) {
                            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
                        }
                    } catch (Throwable ignored) {}

                    String cmd1 = "settings put secure enabled_accessibility_services " + (newServices.isEmpty() ? "null" : newServices);
                    String cmd2 = list.isEmpty() ? "settings put secure accessibility_enabled 0" : "settings put secure accessibility_enabled 1";
                    U.runCommands(context, new String[]{cmd1, cmd2}, false);
                    Log.i(TAG, "Disabled AutoSkipService via root command");
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in disable", t);
            }
        }).start();
    }
}
