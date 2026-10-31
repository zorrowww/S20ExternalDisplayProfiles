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

package com.farmerbb.secondscreen.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

public class AudioRoutingHelper {
    private static final String TAG = "AudioRoutingHelper";

    public static void applyAudioRouting(final Context context) {
        if (context == null) return;
        SharedPreferences prefMain = U.getPrefMain(context);
        boolean forceSpeaker = prefMain.getBoolean("force_phone_speaker", true);
        setForceSpeaker(context, forceSpeaker);
    }

    public static void setForceSpeaker(final Context context, final boolean forceSpeaker) {
        if (context == null) return;

        new Thread(() -> {
            try {
                // 1. In-process execution (app level)
                applyInProcess(context, forceSpeaker);

                // 2. Global dock audio setting
                try {
                    android.provider.Settings.Global.putInt(
                            context.getContentResolver(), "dock_audio_media_enabled", forceSpeaker ? 0 : 1);
                } catch (Throwable ignored) {}

                // 3. Root execution via app_process (UID 0 root, bypasses MODIFY_AUDIO_ROUTING permission)
                if (U.hasElevatedPermissions(context, false)) {
                    String apkPath = context.getPackageCodePath();
                    // Execute each command separately to avoid shell && or ; parser drops
                    U.runCommand(context, "settings put global dock_audio_media_enabled " + (forceSpeaker ? "0" : "1"));
                    U.runCommand(context, "CLASSPATH=" + apkPath + " /system/bin/app_process /system/bin " 
                            + AudioRoutingHelper.class.getName() + " " + forceSpeaker);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to apply audio routing", t);
            }
        }).start();
    }

    private static void applyInProcess(final Context context, final boolean forceSpeaker) {
        try {
            // DeX settings override
            try {
                Bundle b1 = new Bundle();
                b1.putString("key", "hdmi_auto_enter");
                b1.putString("val", forceSpeaker ? "false" : "true");
                context.getContentResolver().call(
                        Uri.parse("content://com.sec.android.desktopmode.uiservice.SettingsProvider"),
                        "setSettings", null, b1);

                Bundle b2 = new Bundle();
                b2.putString("key", "audio_output_to_display");
                b2.putString("val", forceSpeaker ? "false" : "true");
                context.getContentResolver().call(
                        Uri.parse("content://com.sec.android.desktopmode.uiservice.SettingsProvider"),
                        "setSettings", null, b2);

                if (forceSpeaker) {
                    Intent exitDex = new Intent("com.samsung.android.desktopmode.action.EXIT_DESKTOP_MODE");
                    context.sendBroadcast(exitDex);
                }
            } catch (Throwable ignored) {}

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            // Standard Android speakerphone
            try {
                am.setSpeakerphoneOn(forceSpeaker);
            } catch (Throwable ignored) {}

            // Samsung One UI proprietary method: AudioManager.setForceSpeakerOn(boolean)
            try {
                Method m = am.getClass().getMethod("setForceSpeakerOn", boolean.class);
                m.invoke(am, forceSpeaker);
                Log.i(TAG, "In-process setForceSpeakerOn(" + forceSpeaker + ") succeeded");
            } catch (Throwable ignored) {}

            // Android AudioSystem.setForceUse and setParameters
            try {
                Class<?> asCls = Class.forName("android.media.AudioSystem");
                Method setForceUse = asCls.getMethod("setForceUse", int.class, int.class);
                setForceUse.invoke(null, 1, forceSpeaker ? 1 : 0); // FOR_MEDIA
                setForceUse.invoke(null, 3, 0); // FOR_DOCK -> FORCE_NONE

                Method setParameters = asCls.getMethod("setParameters", String.class);
                if (forceSpeaker) {
                    setParameters.invoke(null, "routing=2");
                }
            } catch (Throwable ignored) {}

            // Android 12+ Communication device
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    if (forceSpeaker) {
                        for (AudioDeviceInfo dev : am.getAvailableCommunicationDevices()) {
                            if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                am.setCommunicationDevice(dev);
                                break;
                            }
                        }
                    } else {
                        am.clearCommunicationDevice();
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyInProcess error", t);
        }
    }

    /**
     * Entry point when executed as root (UID 0) via app_process.
     */
    public static void main(String[] args) {
        boolean forceSpeaker = true;
        if (args.length > 0) {
            forceSpeaker = !"false".equalsIgnoreCase(args[0].trim());
        }
        System.out.println("AudioRoutingHelper [UID 0]: forceSpeaker=" + forceSpeaker);

        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }

            // 1. AudioSystem static operations (UID 0 root direct kernel/HAL call, no Context required)
            try {
                Class<?> asCls = Class.forName("android.media.AudioSystem");
                Method setForceUse = asCls.getMethod("setForceUse", int.class, int.class);
                setForceUse.invoke(null, 1, forceSpeaker ? 1 : 0); // FOR_MEDIA -> FORCE_SPEAKER
                setForceUse.invoke(null, 3, 0); // FOR_DOCK -> FORCE_NONE
                System.out.println("Invoked AudioSystem.setForceUse FOR_MEDIA=" + (forceSpeaker ? 1 : 0) + ", FOR_DOCK=0");

                Method setParameters = asCls.getMethod("setParameters", String.class);
                if (forceSpeaker) {
                    setParameters.invoke(null, "routing=2");
                    System.out.println("Invoked AudioSystem.setParameters('routing=2')");
                }
            } catch (Throwable t) {
                System.err.println("AudioSystem static error: " + t.getMessage());
            }

            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("systemMain").invoke(null);
            Context ctx = (Context) atCls.getMethod("getSystemContext").invoke(at);

            // Global dock audio setting
            try {
                android.provider.Settings.Global.putInt(
                        ctx.getContentResolver(), "dock_audio_media_enabled", forceSpeaker ? 0 : 1);
            } catch (Throwable ignored) {}

            // 2. DeX settings override
            try {
                Bundle b1 = new Bundle();
                b1.putString("key", "hdmi_auto_enter");
                b1.putString("val", forceSpeaker ? "false" : "true");
                ctx.getContentResolver().call(
                        Uri.parse("content://com.sec.android.desktopmode.uiservice.SettingsProvider"),
                        "setSettings", null, b1);

                Bundle b2 = new Bundle();
                b2.putString("key", "audio_output_to_display");
                b2.putString("val", forceSpeaker ? "false" : "true");
                ctx.getContentResolver().call(
                        Uri.parse("content://com.sec.android.desktopmode.uiservice.SettingsProvider"),
                        "setSettings", null, b2);

                if (forceSpeaker) {
                    Intent exitDex = new Intent("com.samsung.android.desktopmode.action.EXIT_DESKTOP_MODE");
                    ctx.sendBroadcast(exitDex);
                }
                System.out.println("DeX settings override applied.");
            } catch (Throwable t) {
                System.err.println("DeX override error: " + t.getMessage());
            }

            // 3. AudioManager operations with root privileges
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                try {
                    am.setSpeakerphoneOn(forceSpeaker);
                } catch (Throwable ignored) {}

                // Samsung setForceSpeakerOn
                try {
                    Method m = am.getClass().getMethod("setForceSpeakerOn", boolean.class);
                    m.invoke(am, forceSpeaker);
                    System.out.println("Invoked AudioManager.setForceSpeakerOn(" + forceSpeaker + ")");
                } catch (Throwable t) {
                    System.err.println("setForceSpeakerOn error: " + t.getMessage());
                }

                // AudioProductStrategy preferred device (Root UID 0 bypasses MODIFY_AUDIO_ROUTING check)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        AudioDeviceInfo speakerDevice = null;
                        for (AudioDeviceInfo dev : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                            if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                speakerDevice = dev;
                                break;
                            }
                        }

                        if (speakerDevice != null) {
                            Class<?> apsCls = Class.forName("android.media.audiopolicy.AudioProductStrategy");
                            Class<?> adaCls = Class.forName("android.media.AudioDeviceAttributes");
                            Constructor<?> adaCtor = adaCls.getConstructor(AudioDeviceInfo.class);
                            Object speakerAttr = adaCtor.newInstance(speakerDevice);

                            Method getStrategies = AudioManager.class.getMethod("getAudioProductStrategies");
                            List<?> strategies = (List<?>) getStrategies.invoke(null);
                            Method setPref = AudioManager.class.getMethod("setPreferredDeviceForStrategy", apsCls, adaCls);
                            Method removePref = AudioManager.class.getMethod("removePreferredDeviceForStrategy", apsCls);

                            if (strategies != null) {
                                for (Object s : strategies) {
                                    if (forceSpeaker) {
                                        setPref.invoke(am, s, speakerAttr);
                                    } else {
                                        removePref.invoke(am, s);
                                    }
                                }
                                System.out.println("setPreferredDeviceForStrategy applied for " + strategies.size() + " strategies.");
                            }
                        }
                    } catch (Throwable t) {
                        System.err.println("setPreferredDeviceForStrategy error: " + t.getMessage());
                    }
                }

                // Communication device
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        if (forceSpeaker) {
                            for (AudioDeviceInfo dev : am.getAvailableCommunicationDevices()) {
                                if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                    am.setCommunicationDevice(dev);
                                    break;
                                }
                            }
                        } else {
                            am.clearCommunicationDevice();
                        }
                        System.out.println("CommunicationDevice updated.");
                    } catch (Throwable ignored) {}
                }
            }

            System.out.println("AudioRoutingHelper finished successfully.");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Re-applies audio routing with a short delay (useful after HDMI connection event settles).
     */
    public static void applyAudioRoutingDelayed(final Context context, long delayMillis) {
        if (context == null) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> applyAudioRouting(context), delayMillis);
    }
}
