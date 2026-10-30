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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.farmerbb.secondscreen.util.U;

import java.util.ArrayList;
import java.util.List;

public class AutoSkipService extends AccessibilityService {
    private static final String TAG = "AutoSkipService";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static String sLastSkippedPackage = "";
    private static long sLastSkippedTime = 0;

    private String mRetryingPackage = null;
    private int mRetryCount = 0;
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_INTERVAL_MS = 150;

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

        if (isExcludedPackage(pkg)) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        // If this package was confirmed skipped recently (within 3s), avoid duplicate work
        if (pkg.equals(sLastSkippedPackage) && (now - sLastSkippedTime < 3000)) {
            return;
        }

        processWindowsAndClick(pkg);
    }

    private static boolean isExcludedPackage(String pkg) {
        if (pkg == null) return true;
        String lower = pkg.toLowerCase();
        return lower.startsWith("com.farmerbb.secondscreen")
                || lower.equals("android")
                || lower.equals("com.android.systemui")
                || lower.equals("com.sec.android.app.launcher")
                || lower.contains("honeyboard")
                || lower.contains("inputmethod")
                || lower.contains("ime")
                || lower.contains("keyboard");
    }

    private void processWindowsAndClick(final String pkg) {
        // 1. Check if soft keyboard is currently open on screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                            // Soft keyboard is active - user is actively typing, NOT in a splash ad!
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2. Check if active window has a focused input field (EditText / SearchView)
        AccessibilityNodeInfo activeRoot = null;
        try {
            activeRoot = getRootInActiveWindow();
            if (activeRoot != null) {
                AccessibilityNodeInfo focusedInput = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focusedInput != null) {
                    try { focusedInput.recycle(); } catch (Throwable ignored) {}
                    try { activeRoot.recycle(); } catch (Throwable ignored) {}
                    return; // User is in an active input / search view, never click!
                }
            }
        } catch (Throwable ignored) {}

        // 3. Collect eligible application windows
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                            AccessibilityNodeInfo r = window.getRoot();
                            if (r != null) roots.add(r);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (activeRoot != null && !roots.contains(activeRoot)) {
            roots.add(activeRoot);
        }

        boolean foundAndClicked = false;
        for (AccessibilityNodeInfo root : roots) {
            AccessibilityNodeInfo skipNode = findSkipNode(root);
            if (skipNode != null) {
                performSkipClick(skipNode);
                try {
                    skipNode.recycle();
                } catch (Throwable ignored) {}
                foundAndClicked = true;
                break;
            }
        }

        for (AccessibilityNodeInfo root : roots) {
            try {
                root.recycle();
            } catch (Throwable ignored) {}
        }

        if (foundAndClicked) {
            scheduleRetry(pkg);
        }
    }

    private void scheduleRetry(final String pkg) {
        if (!pkg.equals(mRetryingPackage)) {
            mRetryingPackage = pkg;
            mRetryCount = 0;
        }

        if (mRetryCount >= MAX_RETRIES) {
            mRetryingPackage = null;
            mRetryCount = 0;
            sLastSkippedPackage = pkg;
            sLastSkippedTime = SystemClock.uptimeMillis();
            return;
        }

        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    // Abort if keyboard or focused input is present
                    AccessibilityNodeInfo focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focusedInput != null) {
                        try { focusedInput.recycle(); } catch (Throwable ignored) {}
                        mRetryingPackage = null;
                        return;
                    }

                    AccessibilityNodeInfo skipNode = findSkipNode(root);
                    if (skipNode != null) {
                        mRetryCount++;
                        Log.i(TAG, "Skip button still present in " + pkg + ", retry #" + mRetryCount);
                        performSkipClick(skipNode);
                        try {
                            skipNode.recycle();
                        } catch (Throwable ignored) {}
                        scheduleRetry(pkg);
                    } else {
                        Log.i(TAG, "Skip button gone in " + pkg + ", splash ad successfully dismissed!");
                        mRetryingPackage = null;
                        mRetryCount = 0;
                        sLastSkippedPackage = pkg;
                        sLastSkippedTime = SystemClock.uptimeMillis();
                    }
                } finally {
                    try {
                        root.recycle();
                    } catch (Throwable ignored) {}
                }
            }
        }, RETRY_INTERVAL_MS);
    }

    private AccessibilityNodeInfo findSkipNode(AccessibilityNodeInfo node) {
        if (node == null) return null;

        // Never touch IME or keyboard views
        CharSequence nodePkg = node.getPackageName();
        if (nodePkg != null && isExcludedPackage(nodePkg.toString())) {
            return null;
        }

        if (isSkipNode(node)) {
            return node;
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findSkipNode(child);
                if (found != null) {
                    if (found != child) {
                        try {
                            child.recycle();
                        } catch (Throwable ignored) {}
                    }
                    return found;
                }
                try {
                    child.recycle();
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private boolean isSkipNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (isSkipText(node.getText())) return true;
        if (isSkipText(node.getContentDescription())) return true;
        if (isSkipId(node.getViewIdResourceName())) return true;
        return false;
    }

    /**
     * Strictly matches splash ad skip text.
     * Standalone '关闭' or '×' is deliberately excluded to prevent false clicks on keyboards, search views, or dialogs.
     */
    private static boolean isSkipText(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        if (s.isEmpty() || s.length() > 15) return false;
        if (s.contains("不跳过") || s.contains("不能跳过")) return false;

        // 1. Chinese keywords: must contain "跳过" (e.g. 跳过, 跳过 5s, 跳过(5), 跳过广告, 点击跳过, 跳过/关闭)
        if (s.contains("跳过")) return true;

        // 2. Explicit ad-closing phrases: must contain "广告"
        if (s.contains("关闭广告") || s.contains("跳过广告")) return true;

        // 3. English skip keywords
        String lower = s.toLowerCase();
        if (lower.equals("skip") || lower.startsWith("skip ") || lower.contains("skip ad") || lower.contains("skipad")) {
            return true;
        }
        if (lower.equals("close ad") || lower.equals("close ads")) {
            return true;
        }

        return false;
    }

    /**
     * Strictly matches IDs specific to splash ad skip buttons.
     * Excludes generic keywords like search, keyboard, edit, etc.
     */
    private static boolean isSkipId(String resId) {
        if (resId == null) return false;
        String lower = resId.toLowerCase();
        if (lower.contains("container") || lower.contains("layout") || lower.contains("wrapper")
                || lower.contains("search") || lower.contains("keyboard") || lower.contains("edit")
                || lower.contains("input") || lower.contains("honeyboard")) {
            return false;
        }
        return lower.contains("splash_skip")
                || lower.contains("skip_button")
                || lower.contains("btn_skip")
                || lower.contains("skip_view")
                || lower.contains("count_down")
                || lower.contains("splash_ad_close")
                || lower.contains("btn_close_splash");
    }

    private boolean performSkipClick(AccessibilityNodeInfo target) {
        if (target == null) return false;

        Rect bounds = new Rect();
        target.getBoundsInScreen(bounds);

        // 1. Accessibility ACTION_CLICK
        boolean actionClicked = false;
        if (target.isClickable()) {
            actionClicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        if (!actionClicked) {
            AccessibilityNodeInfo parent = target.getParent();
            int depth = 0;
            while (parent != null && depth < 4) {
                if (parent.isClickable()) {
                    actionClicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    try {
                        parent.recycle();
                    } catch (Throwable ignored) {}
                    break;
                }
                AccessibilityNodeInfo prev = parent;
                parent = parent.getParent();
                try {
                    prev.recycle();
                } catch (Throwable ignored) {}
                depth++;
            }
        }

        // 2. Hardware gesture tap (dual-fire)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && bounds.width() > 0 && bounds.height() > 0) {
            dispatchTap(bounds.centerX(), bounds.centerY());
        }

        return true;
    }

    private void dispatchTap(int x, int y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 50);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        }
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
