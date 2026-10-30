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
        int customWidth = 0;
        int customHeight = 0;

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
                customWidth = Integer.parseInt(args[1]);
                customHeight = Integer.parseInt(args[2]);
            } catch (Exception ignored) {}
        }

        System.out.println("HdmiRotator running: targetRotation=" + targetRotation 
                + " customW=" + customWidth + " customH=" + customHeight);

        boolean success = applyHdmiRotation(targetRotation, customWidth, customHeight);
        System.out.println("HdmiRotator result: " + (success ? "SUCCESS" : "FAILED"));
    }

    /**
     * Executes rotation via app_process with root UID 0.
     */
    public static void rotate(Context context, int rotation) {
        try {
            String apkPath = context.getPackageCodePath();
            String cmd = "CLASSPATH=" + apkPath + " /system/bin/app_process /system/bin " 
                    + HdmiRotator.class.getName() + " " + rotation;
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

                // Determine display framebuffer width & height
                int dispW = customW > 0 ? customW : 1366;
                int dispH = customH > 0 ? customH : 768;

                // Ensure HDMI is landscape base (w >= h)
                int maxDim = Math.max(dispW, dispH);
                int minDim = Math.min(dispW, dispH);

                Rect layerStackRect;
                Rect displayRect;

                if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                    // For portrait source content rotated 90 or 270 degrees in oriented space
                    layerStackRect = new Rect(0, 0, minDim, maxDim);
                    displayRect = new Rect(0, 0, minDim, maxDim);
                } else {
                    // Standard landscape source content
                    layerStackRect = new Rect(0, 0, maxDim, minDim);
                    displayRect = new Rect(0, 0, maxDim, minDim);
                }

                System.out.println("Applying setDisplayProjection: rotation=" + rotation
                        + " layerStackRect=" + layerStackRect + " displayRect=" + displayRect);

                if (setDisplayLayerStack != null) {
                    // Ensure mirroring layerStack 0
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
