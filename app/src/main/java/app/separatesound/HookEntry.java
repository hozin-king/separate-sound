package app.separatesound;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * SeparateSound module entry point (modern libxposed API).
 *
 * <p>For every app that has a route configured in the "routes" remote
 * preferences, this hook forces the app's audio players onto the chosen
 * output device:
 * <ul>
 *   <li>{@code "bluetooth"} &rarr; the connected Bluetooth A2DP device</li>
 *   <li>{@code "speaker"} &rarr; the built-in speaker</li>
 * </ul>
 *
 * <p>Enforcement is done from inside the target process by setting the
 * preferred device on every {@link AudioTrack} / {@link MediaPlayer} the app
 * creates ({@code AudioTrack.Builder.build()} included). If the target device
 * is not present (e.g. Bluetooth disconnected), nothing is forced and the
 * system routing applies — the same natural fallback Samsung uses. All live
 * players are kept in a weak registry and re-applied whenever the set of
 * audio devices changes, so a Bluetooth device that connects after the app
 * started is picked up too.
 *
 * <p>Whether the platform ultimately honours the preferred device depends on
 * the ROM's audio policy; this hook can only request it.
 */
public class HookEntry extends XposedModule {

    static final String PREFS_GROUP = "routes";
    static final String KEY_ENABLED = "enabled";
    static final String ROUTE_BLUETOOTH = "bluetooth";
    static final String ROUTE_SPEAKER = "speaker";
    private static final String OWN_PACKAGE = "app.separatesound";

    /** Route for this process: "bluetooth" or "speaker"; null = stay out. */
    private volatile String route;

    private volatile AudioManager audioManager;
    private volatile boolean deviceCallbackRegistered;

    /** Live AudioTrack/MediaPlayer instances created by the target app. */
    private final Map<Object, Boolean> registry =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final AudioDeviceCallback deviceCallback =
            new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    reapplyAll();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    reapplyAll();
                }
            };

    @Override
    public void onPackageReady(PackageReadyParam param) {
        super.onPackageReady(param);
        final String pkg = param.getPackageName();
        if (pkg == null || OWN_PACKAGE.equals(pkg)) return;

        SharedPreferences prefs;
        try {
            prefs = getRemotePreferences(PREFS_GROUP);
        } catch (Throwable t) {
            return;
        }
        if (!prefs.getBoolean(KEY_ENABLED, false)) return;
        String configured = prefs.getString(pkg, null);
        if (!ROUTE_BLUETOOTH.equals(configured) && !ROUTE_SPEAKER.equals(configured)) return;
        route = configured;

        installAudioHooks();
        installContextHook();
    }

    /** Hook every AudioTrack/MediaPlayer creation path of the target app. */
    private void installAudioHooks() {
        try {
            for (Constructor<?> ctor : AudioTrack.class.getDeclaredConstructors()) {
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    applyPreferredDevice(self != null ? self : result);
                    return result;
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            Method build = AudioTrack.Builder.class.getMethod("build");
            hook(build).intercept(chain -> {
                Object result = chain.proceed();
                applyPreferredDevice(result);
                return result;
            });
        } catch (Throwable ignored) {
        }
        try {
            for (Constructor<?> ctor : MediaPlayer.class.getDeclaredConstructors()) {
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    applyPreferredDevice(self != null ? self : result);
                    return result;
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** Capture the app's AudioManager as soon as its Application is created. */
    private void installContextHook() {
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Context) {
                    initAudioManager((Context) self);
                }
                return result;
            });
        } catch (Throwable ignored) {
        }
    }

    private void initAudioManager(Context context) {
        if (audioManager == null) {
            try {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            } catch (Throwable ignored) {
            }
        }
        if (audioManager != null && !deviceCallbackRegistered) {
            try {
                audioManager.registerAudioDeviceCallback(deviceCallback, null);
                deviceCallbackRegistered = true;
            } catch (Throwable ignored) {
            }
        }
        reapplyAll();
    }

    private AudioManager ensureAudioManager() {
        if (audioManager != null) return audioManager;
        // Fallback before Application.onCreate ran: fetch the app object
        // through ActivityThread (framework-internal, read-only use).
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                initAudioManager((Context) app);
            }
        } catch (Throwable ignored) {
        }
        return audioManager;
    }

    /** The currently connected device matching the configured route, or null. */
    private AudioDeviceInfo findTargetDevice() {
        AudioManager am = ensureAudioManager();
        if (am == null || route == null) return null;
        int wanted = ROUTE_BLUETOOTH.equals(route)
                ? AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                : AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        try {
            AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (devices != null) {
                for (AudioDeviceInfo device : devices) {
                    if (device != null && device.getType() == wanted) return device;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void applyPreferredDevice(Object player) {
        if (player == null || route == null) return;
        if (!(player instanceof AudioTrack) && !(player instanceof MediaPlayer)) return;
        // Register first: if the target device appears later, the device
        // callback re-applies the route to everything in the registry.
        registry.put(player, Boolean.TRUE);
        AudioDeviceInfo device = findTargetDevice();
        if (device != null) {
            setPreferredDevice(player, device);
        }
    }

    private void setPreferredDevice(Object player, AudioDeviceInfo device) {
        try {
            if (player instanceof AudioTrack) {
                ((AudioTrack) player).setPreferredDevice(device);
            } else if (player instanceof MediaPlayer
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // MediaPlayer.setPreferredDevice exists since API 28.
                ((MediaPlayer) player).setPreferredDevice(device);
            }
        } catch (Throwable ignored) {
            // The platform may reject a preferred device; never crash the host.
        }
    }

    private void reapplyAll() {
        if (route == null) return;
        AudioDeviceInfo device = findTargetDevice();
        if (device == null) return;
        synchronized (registry) {
            for (Object player : registry.keySet()) {
                if (player != null) {
                    setPreferredDevice(player, device);
                }
            }
        }
    }
}
