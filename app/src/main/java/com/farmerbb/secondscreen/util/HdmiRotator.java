package com.farmerbb.secondscreen.util;

import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * HdmiRotator controls external physical HDMI display projection and rotation
 * using SurfaceControl.Transaction with root UID 0 permissions.
 */
public class HdmiRotator {

    private static final String TAG = "HdmiRotator";

    public static void main(String[] args) {
        int targetRotation = Surface.ROTATION_0;
        int phoneWidth = 0;
        int phoneHeight = 0;

        if (args.length > 0) {
            String arg0 = args[0].trim().toLowerCase();
            switch (arg0) {
                case "1":
                case "90":
                case "rot_90":
                case "portrait":
                    targetRotation = Surface.ROTATION_90;
                    break;
                case "2":
                case "180":
                case "rot_180":
                    targetRotation = Surface.ROTATION_180;
                    break;
                case "3":
                case "270":
                case "rot_270":
                case "seascape":
                    targetRotation = Surface.ROTATION_270;
                    break;
                case "0":
                case "360":
                case "rot_0":
                case "landscape":
                default:
                    targetRotation = Surface.ROTATION_0;
                    break;
            }
        }

        if (args.length >= 3) {
            try {
                phoneWidth = Integer.parseInt(args[1]);
                phoneHeight = Integer.parseInt(args[2]);
            } catch (Exception ignored) {}
        }

        System.out.println("HdmiRotator running: targetRotation=" + targetRotation 
                + " phoneW=" + phoneWidth + " phoneH=" + phoneHeight);

        boolean success = applyHdmiRotation(targetRotation, phoneWidth, phoneHeight);
        System.out.println("HdmiRotator result: " + (success ? "SUCCESS" : "FAILED"));
    }

    /**
     * Executes rotation via app_process with root UID 0.
     */
    public static void rotate(Context context, int rotation) {
        rotate(context, rotation, 0, 0);
    }

    public static void rotate(Context context, int rotation, int phoneW, int phoneH) {
        try {
            String apkPath = context.getPackageCodePath();
            String cmd = "CLASSPATH=" + apkPath + " /system/bin/app_process /system/bin " 
                    + HdmiRotator.class.getName() + " " + rotation + " " + phoneW + " " + phoneH;
            Log.d(TAG, "Executing root command: " + cmd);
            U.runCommand(context, cmd);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to run HdmiRotator command", t);
        }
    }

    /**
     * Applies projection transformation to external HDMI displays using SurfaceControl.
     */
    public static boolean applyHdmiRotation(int rotation, int customW, int customH) {
        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");

            Method getPhysicalDisplayIds = scClass.getDeclaredMethod("getPhysicalDisplayIds");
            getPhysicalDisplayIds.setAccessible(true);
            long[] physicalDisplayIds = (long[]) getPhysicalDisplayIds.invoke(null);

            Method getInternalDisplayToken = scClass.getDeclaredMethod("getInternalDisplayToken");
            getInternalDisplayToken.setAccessible(true);
            IBinder internalToken = (IBinder) getInternalDisplayToken.invoke(null);

            Method getPhysicalDisplayToken = scClass.getDeclaredMethod("getPhysicalDisplayToken", long.class);
            getPhysicalDisplayToken.setAccessible(true);

            Constructor<?> txCtor = txClass.getDeclaredConstructor();
            txCtor.setAccessible(true);
            Object transaction = txCtor.newInstance();

            Method setDisplayProjection = txClass.getDeclaredMethod("setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);
            setDisplayProjection.setAccessible(true);

            Method setDisplayLayerStack = null;
            try {
                setDisplayLayerStack = txClass.getDeclaredMethod("setDisplayLayerStack", IBinder.class, int.class);
                setDisplayLayerStack.setAccessible(true);
            } catch (Throwable ignored) {}

            Method apply = txClass.getDeclaredMethod("apply");
            apply.setAccessible(true);

            if (physicalDisplayIds == null || physicalDisplayIds.length == 0) {
                System.err.println("No physical displays found!");
                return false;
            }

            System.out.println("Found physical display IDs: " + Arrays.toString(physicalDisplayIds));

            int externalCount = 0;
            for (long id : physicalDisplayIds) {
                IBinder token = (IBinder) getPhysicalDisplayToken.invoke(null, id);
                if (token == null) continue;

                boolean isInternal = (internalToken != null && internalToken.equals(token));
                if (isInternal) {
                    System.out.println("Display ID " + id + " is internal display. Skipping.");
                    continue;
                }

                externalCount++;
                System.out.println("Configuring external display ID " + id);

                // Physical external display dimensions (1366x768 base landscape)
                int maxDim = 1366;
                int minDim = 768;

                // Source content dimensions (from phone Display 0 / layerStack 0)
                int srcW = customW > 0 ? customW : (rotation == Surface.ROTATION_0 ? maxDim : minDim);
                int srcH = customH > 0 ? customH : (rotation == Surface.ROTATION_0 ? minDim : maxDim);

                Rect layerStackRect;
                Rect displayRect;

                if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                    // --- 方案二：竖屏翻转方案 (Flip Scheme for Vertical) ---
                    // 手机陀螺仪检测到竖立或视频竖屏全屏时，将 HDMI 信号旋转 90°/270°，直接映射 768x1366 满屏无黑边
                    layerStackRect = new Rect(0, 0, minDim, maxDim);
                    displayRect = new Rect(0, 0, minDim, maxDim);
                } else {
                    // --- 方案一：横屏走之前的原生方案 (Original Scheme for Landscape) ---
                    // 手机陀螺仪检测到横置或横屏视频全屏时
                    if (srcW >= srcH) {
                        // 手机在横屏状态或横屏全屏视频播放：1366x768 满屏映射
                        layerStackRect = new Rect(0, 0, maxDim, minDim);
                        displayRect = new Rect(0, 0, maxDim, minDim);
                    } else {
                        // 手机在竖屏状态：768x1366 居中等比映射 (467..899)，左右黑边居中，绝不出现右侧半屏黑屏截断
                        layerStackRect = new Rect(0, 0, minDim, maxDim);
                        int fitW = Math.round((float) minDim * minDim / maxDim);
                        int offset = (maxDim - fitW) / 2;
                        displayRect = new Rect(offset, 0, offset + fitW, minDim);
                    }
                }

                System.out.println("Applying setDisplayProjection: rotation=" + rotation
                        + " srcW=" + srcW + " srcH=" + srcH
                        + " layerStackRect=" + layerStackRect + " displayRect=" + displayRect);

                if (setDisplayLayerStack != null) {
                    // HDMI mirrors layerStack 0
                    setDisplayLayerStack.invoke(transaction, token, 0);
                }

                setDisplayProjection.invoke(transaction, token, rotation, layerStackRect, displayRect);
            }

            if (externalCount == 0) {
                System.err.println("No external physical display found among: " + Arrays.toString(physicalDisplayIds));
                return false;
            }

            apply.invoke(transaction);
            System.out.println("SurfaceControl transaction successfully applied for " + externalCount + " external display(s).");
            return true;
        } catch (Throwable t) {
            System.err.println("Error applying HDMI rotation: " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }
}
