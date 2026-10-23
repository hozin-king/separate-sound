package app.separatesound;

import android.app.Application;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * App-process entry point.
 *
 * <p>Binds LSPosed's {@link XposedService} and exposes the shared "routes"
 * remote preferences — the same preference group the hook reads inside target
 * apps. The settings UI edits through this class.
 *
 * <p>A plain local mirror of the same keys is kept so the UI stays usable
 * before the service connects (or when the module is not enabled yet); on
 * bind, local entries are pushed to the remote preferences, so nothing the
 * user configured is lost.
 */
public class SeparateSoundApp extends Application
        implements XposedServiceHelper.OnServiceListener {

    public static final String PREFS_GROUP = "routes";
    public static final String KEY_ENABLED = "enabled";
    private static final String LOCAL_PREFS = "routes_local";

    private static volatile XposedService service;
    private static volatile SharedPreferences remotePrefs;
    private static final List<Runnable> serviceListeners = new CopyOnWriteArrayList<>();

    private SharedPreferences localPrefs;

    public static boolean isServiceConnected() {
        return remotePrefs != null;
    }

    public static void addServiceListener(Runnable listener) {
        serviceListeners.add(listener);
    }

    public static void removeServiceListener(Runnable listener) {
        serviceListeners.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localPrefs = getSharedPreferences(LOCAL_PREFS, MODE_PRIVATE);
        XposedServiceHelper.registerListener(this);
    }

    public SharedPreferences getLocalPrefs() {
        return localPrefs;
    }

    /** Current value of a route key ("bluetooth"/"speaker"/null) from the local mirror. */
    public String getRoute(String packageName) {
        return localPrefs.getString(packageName, null);
    }

    public boolean isEnabled() {
        return localPrefs.getBoolean(KEY_ENABLED, false);
    }

    /** Save a per-app route; null removes the entry (follow the system). */
    public void putRoute(String packageName, String route) {
        SharedPreferences.Editor local = localPrefs.edit();
        if (route == null) {
            local.remove(packageName);
        } else {
            local.putString(packageName, route);
        }
        local.apply();
        writeRemote(editor -> {
            if (route == null) {
                editor.remove(packageName);
            } else {
                editor.putString(packageName, route);
            }
        });
    }

    public void putEnabled(boolean enabled) {
        localPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        writeRemote(editor -> editor.putBoolean(KEY_ENABLED, enabled));
    }

    private interface RemoteEdit {
        void apply(SharedPreferences.Editor editor);
    }

    private void writeRemote(RemoteEdit edit) {
        SharedPreferences rp = remotePrefs;
        if (rp == null) return;
        try {
            SharedPreferences.Editor editor = rp.edit();
            edit.apply(editor);
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onServiceBind(XposedService boundService) {
        service = boundService;
        try {
            remotePrefs = boundService.getRemotePreferences(PREFS_GROUP);
            syncLocalAndRemote();
        } catch (Throwable t) {
            remotePrefs = null;
        }
        notifyServiceListeners();
    }

    @Override
    public void onServiceDied(XposedService deadService) {
        if (service == deadService) {
            service = null;
        }
        remotePrefs = null;
        notifyServiceListeners();
    }

    /** Two-way merge: local UI choices win; remote-only keys are pulled in. */
    private void syncLocalAndRemote() {
        SharedPreferences rp = remotePrefs;
        if (rp == null || localPrefs == null) return;
        try {
            Map<String, ?> localAll = localPrefs.getAll();
            SharedPreferences.Editor remoteEditor = rp.edit();
            for (Map.Entry<String, ?> entry : localAll.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    remoteEditor.putString(entry.getKey(), (String) value);
                } else if (value instanceof Boolean) {
                    remoteEditor.putBoolean(entry.getKey(), (Boolean) value);
                }
            }
            remoteEditor.apply();

            SharedPreferences.Editor localEditor = localPrefs.edit();
            for (Map.Entry<String, ?> entry : rp.getAll().entrySet()) {
                if (!localAll.containsKey(entry.getKey())) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        localEditor.putString(entry.getKey(), (String) value);
                    } else if (value instanceof Boolean) {
                        localEditor.putBoolean(entry.getKey(), (Boolean) value);
                    }
                }
            }
            localEditor.apply();
        } catch (Throwable ignored) {
        }
    }

    private void notifyServiceListeners() {
        for (Runnable listener : serviceListeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
