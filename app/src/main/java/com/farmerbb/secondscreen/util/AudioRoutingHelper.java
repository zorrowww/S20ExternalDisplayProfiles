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
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;

                // 1. Samsung One UI proprietary method: AudioManager.setForceSpeakerOn(boolean)
                try {
                    Method m = am.getClass().getMethod("setForceSpeakerOn", boolean.class);
                    m.invoke(am, forceSpeaker);
                    Log.i(TAG, "Invoked AudioManager.setForceSpeakerOn(" + forceSpeaker + ")");
                } catch (Throwable t) {
                    Log.d(TAG, "setForceSpeakerOn not available: " + t.getMessage());
                }

                // 2. Android AudioSystem.setForceUse(FOR_MEDIA = 1, forceSpeaker ? FORCE_SPEAKER = 1 : FORCE_NONE = 0)
                try {
                    Class<?> asCls = Class.forName("android.media.AudioSystem");
                    Method m = asCls.getMethod("setForceUse", int.class, int.class);
                    m.invoke(null, 1, forceSpeaker ? 1 : 0);
                    Log.i(TAG, "Invoked AudioSystem.setForceUse(1, " + (forceSpeaker ? 1 : 0) + ")");
                } catch (Throwable t) {
                    Log.d(TAG, "setForceUse not available: " + t.getMessage());
                }

                // 3. Android 11+ AudioProductStrategy preferred device
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
                            Method getStrategies = AudioManager.class.getMethod("getAudioProductStrategies");
                            List<?> strategies = (List<?>) getStrategies.invoke(null);
                            Method setPref = AudioManager.class.getMethod("setPreferredDeviceForStrategy", apsCls, AudioDeviceInfo.class);
                            Method removePref = AudioManager.class.getMethod("removePreferredDeviceForStrategy", apsCls);

                            if (strategies != null) {
                                for (Object s : strategies) {
                                    if (forceSpeaker) {
                                        setPref.invoke(am, s, speakerDevice);
                                    } else {
                                        removePref.invoke(am, s);
                                    }
                                }
                                Log.i(TAG, "Applied setPreferredDeviceForStrategy for speaker: " + forceSpeaker);
                            }
                        }
                    } catch (Throwable t) {
                        Log.d(TAG, "setPreferredDeviceForStrategy not available: " + t.getMessage());
                    }
                }

                // 4. Android 12+ Communication device (for calls/system routing)
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
                Log.e(TAG, "Failed to apply audio routing", t);
            }
        }).start();
    }

    /**
     * Re-applies audio routing with a short delay (useful after HDMI connection event settles).
     */
    public static void applyAudioRoutingDelayed(final Context context, long delayMillis) {
        if (context == null) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> applyAudioRouting(context), delayMillis);
    }
}
