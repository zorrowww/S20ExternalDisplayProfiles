/* Copyright 2024 SecondScreen Project
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

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Directly registers an IRotationWatcher callback with Android's WindowManagerService.
 * This guarantees 100% synchronization with the phone's physical or app-driven screen rotation
 * without relying on sensor tilt angles or debouncing.
 */
public class RotationWatcher {
    private static final String TAG = "RotationWatcher";

    public interface OnRotationChangedListener {
        void onRotationChanged(int rotation);
    }

    private Object windowManager;
    private Object watcherProxy;
    private Method removeRotationWatcherMethod;
    private final OnRotationChangedListener listener;

    public RotationWatcher(OnRotationChangedListener listener) {
        this.listener = listener;
    }

    public synchronized void register() {
        if (windowManager != null) {
            return;
        }

        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = smClass.getMethod("getService", String.class);
            IBinder windowServiceBinder = (IBinder) getServiceMethod.invoke(null, "window");
            if (windowServiceBinder == null) {
                Log.e(TAG, "Window service binder is null");
                return;
            }

            Class<?> wmStubClass = Class.forName("android.view.IWindowManager$Stub");
            Method asInterfaceMethod = wmStubClass.getMethod("asInterface", IBinder.class);
            windowManager = asInterfaceMethod.invoke(null, windowServiceBinder);
            if (windowManager == null) {
                Log.e(TAG, "WindowManager interface is null");
                return;
            }

            final Class<?> iRotationWatcherClass = Class.forName("android.view.IRotationWatcher");
            final Binder binder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == IBinder.FIRST_CALL_TRANSACTION + 0) { // TRANSACTION_onRotationChanged
                        data.enforceInterface("android.view.IRotationWatcher");
                        int rotation = data.readInt();
                        if (listener != null) {
                            listener.onRotationChanged(rotation);
                        }
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };

            InvocationHandler invocationHandler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("asBinder".equals(name)) {
                        return binder;
                    }
                    if ("onRotationChanged".equals(name)) {
                        if (args != null && args.length > 0 && args[0] instanceof Integer) {
                            if (listener != null) {
                                listener.onRotationChanged((Integer) args[0]);
                            }
                        }
                        return null;
                    }
                    if ("equals".equals(name)) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name)) {
                        return "RotationWatcherProxy";
                    }
                    return null;
                }
            };

            watcherProxy = Proxy.newProxyInstance(
                    iRotationWatcherClass.getClassLoader(),
                    new Class<?>[]{iRotationWatcherClass},
                    invocationHandler
            );

            Method watchRotationMethod = windowManager.getClass().getMethod(
                    "watchRotation", iRotationWatcherClass, int.class);
            removeRotationWatcherMethod = windowManager.getClass().getMethod(
                    "removeRotationWatcher", iRotationWatcherClass);

            int initialRotation = (Integer) watchRotationMethod.invoke(
                    windowManager, watcherProxy, Display.DEFAULT_DISPLAY);
            Log.d(TAG, "watchRotation registered successfully! Initial rotation: " + initialRotation);

            if (listener != null) {
                listener.onRotationChanged(initialRotation);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to register watchRotation", t);
        }
    }

    public synchronized void unregister() {
        if (windowManager != null && watcherProxy != null && removeRotationWatcherMethod != null) {
            try {
                removeRotationWatcherMethod.invoke(windowManager, watcherProxy);
                Log.d(TAG, "watchRotation unregistered successfully");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to unregister watchRotation", t);
            }
            windowManager = null;
            watcherProxy = null;
            removeRotationWatcherMethod = null;
        }
    }
}
