package app.separatesound;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings UI for SeparateSound (plain framework widgets, no UI libraries).
 *
 * <p>Layout, top to bottom: service status, master switch, "user apps only"
 * filter, hide-launcher-icon switch, search field, the installed-app list with
 * a per-app route (System / Bluetooth / Speaker), and an honest footer note.
 * Every change is written immediately through {@link SeparateSoundApp}, which
 * mirrors it to the LSPosed remote preferences.
 */
public class MainActivity extends Activity {

    private static final String ROUTE_BLUETOOTH = "bluetooth";
    private static final String ROUTE_SPEAKER = "speaker";
    private static final String[] ROUTE_LABELS = {"Ikuti sistem", "Bluetooth", "Speaker HP"};

    private SeparateSoundApp app;
    private TextView statusView;
    private ListView listView;
    private AppListAdapter adapter;

    private List<AppItem> allApps = new ArrayList<>();
    private boolean userOnly = true;
    private String query = "";
    private final Map<String, Drawable> iconCache = new HashMap<>();

    private final Runnable serviceListener = () -> runOnUiThread(this::refreshStatus);

    private static class AppItem {
        ApplicationInfo info;
        String label;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (SeparateSoundApp) getApplication();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));

        TextView title = new TextView(this);
        title.setText("SeparateSound");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Atur keluaran suara tiap aplikasi: Bluetooth atau speaker HP.");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusView);
        refreshStatus();

        Switch masterSwitch = new Switch(this);
        masterSwitch.setText("Aktifkan Separate Sound");
        masterSwitch.setTextSize(16);
        masterSwitch.setChecked(app.isEnabled());
        masterSwitch.setOnCheckedChangeListener((button, checked) -> app.putEnabled(checked));
        root.addView(masterSwitch, marginParams(dp(10)));

        Switch userOnlySwitch = new Switch(this);
        userOnlySwitch.setText("Hanya aplikasi pengguna");
        userOnlySwitch.setChecked(true);
        userOnlySwitch.setOnCheckedChangeListener((button, checked) -> {
            userOnly = checked;
            applyFilter();
        });
        root.addView(userOnlySwitch, marginParams(dp(6)));

        Switch hideIconSwitch = new Switch(this);
        hideIconSwitch.setText("Sembunyikan ikon launcher");
        hideIconSwitch.setChecked(isIconHidden());
        hideIconSwitch.setOnCheckedChangeListener((button, checked) -> setIconHidden(checked));
        root.addView(hideIconSwitch, marginParams(dp(6)));

        TextView hideIconWarning = new TextView(this);
        hideIconWarning.setText("Setelah ikon disembunyikan, buka pengaturan dari LSPosed Manager "
                + "(daftar modul → SeparateSound).");
        hideIconWarning.setTextSize(12);
        hideIconWarning.setTextColor(Color.DKGRAY);
        root.addView(hideIconWarning);

        EditText search = new EditText(this);
        search.setHint("Cari aplikasi…");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(search, marginParams(dp(10)));

        listView = new ListView(this);
        adapter = new AppListAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppItem item = (AppItem) adapter.getItem(position);
            if (item != null) showRouteDialog(item);
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listView, listParams);

        TextView footer = new TextView(this);
        footer.setText("Catatan: penegakan rute bergantung pada kebijakan audio ROM; "
                + "bila sistem mengabaikan rute pilihan, aplikasi tidak dapat memaksanya. "
                + "Setelah mengubah rute/scope: aktifkan modul di LSPosed, paksa berhenti "
                + "aplikasi target, lalu buka lagi.");
        footer.setTextSize(12);
        footer.setTextColor(Color.DKGRAY);
        footer.setPadding(0, dp(8), 0, dp(4));
        root.addView(footer);

        setContentView(root);

        SeparateSoundApp.addServiceListener(serviceListener);
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onDestroy() {
        SeparateSoundApp.removeServiceListener(serviceListener);
        super.onDestroy();
    }

    private void refreshStatus() {
        if (statusView == null) return;
        if (SeparateSoundApp.isServiceConnected()) {
            statusView.setText("Status: layanan LSPosed terhubung — modul aktif.");
            statusView.setTextColor(Color.rgb(27, 94, 32));
        } else {
            statusView.setText("Status: layanan LSPosed belum terhubung. Aktifkan modul di "
                    + "LSPosed dan centang scope aplikasi target; perubahan tetap disimpan "
                    + "dan disinkronkan saat layanan terhubung.");
            statusView.setTextColor(Color.rgb(160, 60, 0));
        }
    }

    // ------------------------------------------------------------------ apps

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> infos = pm.getInstalledApplications(0);
            List<AppItem> items = new ArrayList<>();
            for (ApplicationInfo info : infos) {
                if (info.packageName.equals(getPackageName())) continue;
                AppItem item = new AppItem();
                item.info = info;
                CharSequence label = pm.getApplicationLabel(info);
                item.label = label != null ? label.toString() : info.packageName;
                items.add(item);
            }
            Collator collator = Collator.getInstance();
            items.sort((a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> {
                allApps = items;
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        List<AppItem> filtered = new ArrayList<>();
        for (AppItem item : allApps) {
            if (userOnly && (item.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (!query.isEmpty()
                    && !item.label.toLowerCase(Locale.ROOT).contains(query)
                    && !item.info.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            filtered.add(item);
        }
        adapter.setItems(filtered);
    }

    private void showRouteDialog(AppItem item) {
        String current = app.getRoute(item.info.packageName);
        int checked = ROUTE_BLUETOOTH.equals(current) ? 1 : ROUTE_SPEAKER.equals(current) ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle(item.label)
                .setSingleChoiceItems(ROUTE_LABELS, checked, (dialog, which) -> {
                    String value = which == 1 ? ROUTE_BLUETOOTH : which == 2 ? ROUTE_SPEAKER : null;
                    app.putRoute(item.info.packageName, value);
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private String routeLabel(String packageName) {
        String route = app.getRoute(packageName);
        if (ROUTE_BLUETOOTH.equals(route)) return "Bluetooth";
        if (ROUTE_SPEAKER.equals(route)) return "Speaker";
        return "Sistem";
    }

    // ------------------------------------------------------------ hide icon

    private ComponentName aliasComponent() {
        return new ComponentName(this, "app.separatesound.MainActivityAlias");
    }

    private boolean isIconHidden() {
        int state = getPackageManager().getComponentEnabledSetting(aliasComponent());
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setIconHidden(boolean hidden) {
        getPackageManager().setComponentEnabledSetting(
                aliasComponent(),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    // --------------------------------------------------------------- adapter

    private class AppListAdapter extends BaseAdapter {
        private List<AppItem> items = new ArrayList<>();

        void setItems(List<AppItem> newItems) {
            items = newItems;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(8), dp(8), dp(8));

                ImageView icon = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams iconParams =
                        new LinearLayout.LayoutParams(dp(40), dp(40));
                iconParams.rightMargin = dp(12);
                row.addView(icon, iconParams);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(MainActivity.this);
                name.setTextSize(15);
                TextView pkg = new TextView(MainActivity.this);
                pkg.setTextSize(11);
                pkg.setTextColor(Color.GRAY);
                texts.addView(name);
                texts.addView(pkg);
                row.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView route = new TextView(MainActivity.this);
                route.setTextSize(13);
                route.setTextColor(Color.rgb(21, 101, 192));
                row.addView(route);

                holder = new Holder();
                holder.icon = icon;
                holder.name = name;
                holder.pkg = pkg;
                holder.route = route;
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (Holder) convertView.getTag();
            }

            AppItem item = items.get(position);
            holder.name.setText(item.label);
            holder.pkg.setText(item.info.packageName);
            holder.route.setText(routeLabel(item.info.packageName));
            holder.icon.setImageDrawable(loadIcon(item.info.packageName));
            return convertView;
        }

        private Drawable loadIcon(String packageName) {
            Drawable cached = iconCache.get(packageName);
            if (cached != null) return cached;
            try {
                Drawable icon = getPackageManager().getApplicationIcon(packageName);
                iconCache.put(packageName, icon);
                return icon;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static class Holder {
        ImageView icon;
        TextView name;
        TextView pkg;
        TextView route;
    }

    // ----------------------------------------------------------------- utils

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams marginParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        return params;
    }
}
