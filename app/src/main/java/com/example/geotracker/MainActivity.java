package com.example.geotracker;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.navigation.NavigationView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private static final int PERMISSIONS_REQUEST_LOCATION = 100;
    private static final String PREF_TRACKS = "tracks_json";
    private static final String PREF_VISIBLE = "tracks_visible_json";
    private static final String PREF_CURRENT = "tracks_current_name";
    private static final String PREF_CONTINUOUS_MODE = "continuous_mode";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private TextView textView;
    private MapView map;
    private Button btnToggleContinuous;

    private DrawerLayout drawerLayout;
    private ActionBarDrawerToggle drawerToggle;

    public static class TrackInfo {
        String name;
        String filename;
        int color;

        public TrackInfo(String name, String filename, int color) {
            this.name = name;
            this.filename = filename;
            this.color = color;
        }
    }

    // CSV Record (mit Timestamp als eindeutige ID)
    static class PointRecord {
        String ts;
        String type;
        double lat;
        double lon;
    }

    // Meta zu einem Punkt (Kommentar + Bild(e))
    static class PointMeta {
        String comment = "";
        ArrayList<String> imageUris = new ArrayList<>();
    }

    static class MarkerKey {
        String trackFilename;
        String ts;

        MarkerKey(String trackFilename, String ts) {
            this.trackFilename = trackFilename;
            this.ts = ts;
        }
    }

    private final List<TrackInfo> tracks = new ArrayList<>();
    private boolean[] visibleTracks = new boolean[0];
    private TrackInfo currentTrack = null;
    private boolean continuousMode = false;

    private final Gson gson = new Gson();

    // TrackFilename -> (Timestamp -> Meta)
    private final Map<String, Map<String, PointMeta>> metaCache = new HashMap<>();

    // Bild-Picker / Kamera
    private ActivityResultLauncher<String[]> pickImageLauncher;
    private ActivityResultLauncher<Uri> takePictureLauncher;

    private TrackInfo pendingImageTrack = null;
    private String pendingImageTs = null;
    private Uri pendingCameraUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawerLayout = findViewById(R.id.drawer_layout);
        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        drawerToggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(drawerToggle);
        drawerToggle.syncState();

        Context ctx = getApplicationContext();
        org.osmdroid.config.Configuration.getInstance()
                .load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));

        textView = findViewById(R.id.tv_active_geo);
        map = findViewById(R.id.map);
        map.setMultiTouchControls(true);

        initActivityResultLaunchers();

        Button btnSaveLocation = findViewById(R.id.btn_save_location);
        btnSaveLocation.setOnClickListener(v -> {
            if (continuousMode) {
                addHighlightPoint();
            } else {
                saveLocationToCSV();
            }
            loadAllTracksAndUpdateMap();
        });

        Button btnUpdateMap = findViewById(R.id.btn_update_map);
        btnUpdateMap.setOnClickListener(v -> {
            loadAllTracksAndUpdateMap();
            Toast.makeText(this, "Karte aktualisiert", Toast.LENGTH_SHORT).show();
        });

        Button btnShareCsv = findViewById(R.id.btn_share_csv);
        btnShareCsv.setOnClickListener(v -> showExportDialog());

        btnToggleContinuous = findViewById(R.id.btn_toggle_continuous);
        btnToggleContinuous.setOnClickListener(v -> toggleContinuousMode());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    textView.setText("Standort nicht verfügbar");
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        String coords = "Latitude: " + location.getLatitude() +
                                "\nLongitude: " + location.getLongitude() +
                                (continuousMode ? "\n[CONTINUOUS MODE]" : "");
                        textView.setText(coords);

                        if (continuousMode && currentTrack != null) {
                            saveLocationContinuous(location);
                        }
                    }
                }
            }
        };

        loadTracksFromPrefs();
        loadVisibleFromPrefs();
        loadCurrentTrackFromPrefs();
        loadContinuousModeFromPrefs();

        if (tracks.isEmpty()) {
            TrackInfo t = new TrackInfo("Standard", "track_standard.csv", 0xFF0000FF);
            tracks.add(t);
            currentTrack = t;
            visibleTracks = new boolean[]{true};
            saveAllTrackPrefs();
            ensureCsvHasHeader(new File(getFilesDir(), t.filename));
        }

        updateToggleButtonUI();
        loadAllTracksAndUpdateMap();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSIONS_REQUEST_LOCATION);
        } else {
            startLocationUpdates();
        }
    }

    private void initActivityResultLaunchers() {
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null || pendingImageTrack == null || pendingImageTs == null) return;

                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (Exception ignored) { }

                    Map<String, PointMeta> meta = getMetaMap(pendingImageTrack);
                    PointMeta pm = meta.getOrDefault(pendingImageTs, new PointMeta());
                    pm.imageUris.add(uri.toString());
                    meta.put(pendingImageTs, pm);
                    saveMetaForTrack(pendingImageTrack, meta);

                    pendingImageTrack = null;
                    pendingImageTs = null;

                    loadAllTracksAndUpdateMap();
                }
        );

        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (!success || pendingCameraUri == null || pendingImageTrack == null || pendingImageTs == null) {
                        pendingCameraUri = null;
                        pendingImageTrack = null;
                        pendingImageTs = null;
                        return;
                    }

                    Map<String, PointMeta> meta = getMetaMap(pendingImageTrack);
                    PointMeta pm = meta.getOrDefault(pendingImageTs, new PointMeta());
                    pm.imageUris.add(pendingCameraUri.toString());
                    meta.put(pendingImageTs, pm);
                    saveMetaForTrack(pendingImageTrack, meta);

                    pendingCameraUri = null;
                    pendingImageTrack = null;
                    pendingImageTs = null;

                    loadAllTracksAndUpdateMap();
                }
        );
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_home) {
            Toast.makeText(this, "Home", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.nav_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        } else if (id == R.id.nav_new_track) {
            showCreateTrackDialog();
        } else if (id == R.id.nav_select_track) {
            showSelectCurrentTrackDialog();
        } else if (id == R.id.nav_visibility) {
            showTrackVisibilityDialog();
        } else if (id == R.id.nav_export_track) {
            showExportDialog();
        } else if (id == R.id.nav_delete_track) {
            showDeleteTrackDialog();
        } else if (id == R.id.nav_toggle_continuous) {
            toggleContinuousMode();
        }

        drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
        return true;
    }

    private void toggleContinuousMode() {
        continuousMode = !continuousMode;
        saveContinuousModeToPrefs();

        String mode = continuousMode ? "CONTINUOUS MODE AKTIV" : "Normaler Modus";
        Toast.makeText(this, mode, Toast.LENGTH_LONG).show();

        updateToggleButtonUI();
        loadAllTracksAndUpdateMap();
    }

    private void updateToggleButtonUI() {
        if (btnToggleContinuous != null) {
            if (continuousMode) {
                btnToggleContinuous.setText("Continuous: AN");
                btnToggleContinuous.setBackgroundColor(0xFF4CAF50);
            } else {
                btnToggleContinuous.setText("Continuous: AUS");
                btnToggleContinuous.setBackgroundColor(0xFFFF5722);
            }
        }
    }

    private void addHighlightPoint() {
        if (currentTrack == null) {
            Toast.makeText(this, "Kein Track ausgewählt", Toast.LENGTH_SHORT).show();
            return;
        }

        String data = textView.getText().toString();
        String[] lines = data.split("\n");
        if (lines.length < 2) {
            Toast.makeText(this, "Ungültige Koordinaten", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            double lat = Double.parseDouble(lines[0].split(":")[1].trim());
            double lon = Double.parseDouble(lines[1].split(":")[1].trim());

            File file = new File(getFilesDir(), currentTrack.filename);
            ensureCsvHasHeader(file);

            String timestamp = LocalDateTime.now().toString();
            String row = timestamp + ",HIGHLIGHT," + lat + "," + lon + "\n";

            try (FileOutputStream fos = openFileOutput(currentTrack.filename, MODE_APPEND)) {
                fos.write(row.getBytes());
                Toast.makeText(this, "Highlight-Punkt gespeichert", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Fehler beim Speichern", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveLocationContinuous(Location location) {
        if (currentTrack == null) return;

        try {
            File file = new File(getFilesDir(), currentTrack.filename);
            ensureCsvHasHeader(file);

            String timestamp = LocalDateTime.now().toString();
            String row = timestamp + ",CONTINUOUS," + location.getLatitude() + "," + location.getLongitude() + "\n";

            try (FileOutputStream fos = openFileOutput(currentTrack.filename, MODE_APPEND)) {
                fos.write(row.getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void ensureCsvHasHeader(File file) {
        if (!file.exists()) {
            try (FileOutputStream fos = openFileOutput(file.getName(), MODE_PRIVATE)) {
                String header = "Timestamp,Type,Latitude,Longitude\n";
                fos.write(header.getBytes());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void saveLocationToCSV() {
        if (currentTrack == null) {
            Toast.makeText(this, "Kein Track ausgewählt", Toast.LENGTH_SHORT).show();
            return;
        }

        String data = textView.getText().toString();
        String[] lines = data.split("\n");
        if (lines.length < 2) {
            Toast.makeText(this, "Ungültige Koordinaten", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            double lat = Double.parseDouble(lines[0].split(":")[1].trim());
            double lon = Double.parseDouble(lines[1].split(":")[1].trim());

            File file = new File(getFilesDir(), currentTrack.filename);
            ensureCsvHasHeader(file);

            String timestamp = LocalDateTime.now().toString();
            String row = timestamp + ",MANUAL," + lat + "," + lon + "\n";

            try (FileOutputStream fos = openFileOutput(currentTrack.filename, MODE_APPEND)) {
                fos.write(row.getBytes());
                Toast.makeText(this, "Koordinaten gespeichert", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Fehler beim Speichern", Toast.LENGTH_SHORT).show();
        }
    }

    private List<PointRecord> loadRecordsFromCsv(String filename) {
        List<PointRecord> result = new ArrayList<>();
        try (FileInputStream fis = openFileInput(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 4) continue;

                PointRecord r = new PointRecord();
                r.ts = parts[0].trim();
                r.type = parts[1].trim();
                r.lat = Double.parseDouble(parts[2].trim());
                r.lon = Double.parseDouble(parts[3].trim());
                result.add(r);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private List<PointRecord> filterHighlight(List<PointRecord> all) {
        List<PointRecord> res = new ArrayList<>();
        for (PointRecord r : all) {
            if ("HIGHLIGHT".equals(r.type)) res.add(r);
        }
        return res;
    }

    private List<GeoPoint> toGeoPoints(List<PointRecord> records) {
        List<GeoPoint> pts = new ArrayList<>(records.size());
        for (PointRecord r : records) pts.add(new GeoPoint(r.lat, r.lon));
        return pts;
    }

    private void loadAllTracksAndUpdateMap() {
        map.getOverlays().clear();

        for (int i = 0; i < tracks.size(); i++) {
            if (visibleTracks.length <= i || !visibleTracks[i]) continue;

            TrackInfo t = tracks.get(i);
            List<PointRecord> allRecords = loadRecordsFromCsv(t.filename);
            List<PointRecord> markerRecords = continuousMode ? filterHighlight(allRecords) : allRecords;

            Map<String, PointMeta> meta = getMetaMap(t);

            for (PointRecord r : markerRecords) {
                Marker m = new Marker(map);
                m.setPosition(new GeoPoint(r.lat, r.lon));

                String baseTitle = t.name + ("HIGHLIGHT".equals(r.type) ? " (Highlight)" : "");
                m.setTitle(baseTitle);

                PointMeta pm = meta.get(r.ts);
                if (pm != null && pm.comment != null && !pm.comment.trim().isEmpty()) {
                    m.setSnippet(pm.comment.trim());
                } else {
                    m.setSnippet("");
                }

                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                m.setRelatedObject(new MarkerKey(t.filename, r.ts));

                m.setOnMarkerClickListener((marker, mapView) -> {
                    MarkerKey key = (MarkerKey) marker.getRelatedObject();
                    TrackInfo track = findTrackByFilename(key.trackFilename);
                    if (track == null) return true;
                    showMarkerEditDialog(track, key.ts);
                    return true;
                });

                map.getOverlays().add(m);
            }

            // Polyline immer aus allen Punkten
            List<GeoPoint> allPts = toGeoPoints(allRecords);
            if (allPts.size() > 1) {
                Polyline line = new Polyline(map);
                line.setPoints(allPts);
                line.setColor(t.color);
                line.setWidth(continuousMode ? 8f : 15f);
                map.getOverlays().add(line);
            }
        }

        if (currentTrack != null) {
            List<PointRecord> pts = loadRecordsFromCsv(currentTrack.filename);
            if (!pts.isEmpty()) {
                PointRecord last = pts.get(pts.size() - 1);
                map.getController().setCenter(new GeoPoint(last.lat, last.lon));
                map.getController().setZoom(continuousMode ? 18 : 15);
            }
        }

        map.invalidate();
    }

    private TrackInfo findTrackByFilename(String filename) {
        for (TrackInfo t : tracks) {
            if (t.filename.equals(filename)) return t;
        }
        return null;
    }

    private void showMarkerEditDialog(TrackInfo track, String ts) {
        Map<String, PointMeta> meta = getMetaMap(track);
        PointMeta pm = meta.getOrDefault(ts, new PointMeta());

        // UI programmatic (kein extra layout nötig)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        EditText etComment = new EditText(this);
        etComment.setHint("Kommentar");
        etComment.setText(pm.comment != null ? pm.comment : "");
        root.addView(etComment, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvImages = new TextView(this);
        tvImages.setText(imageSummary(pm));
        root.addView(tvImages);

        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight((int) (220 * getResources().getDisplayMetrics().density));
        Uri first = firstImageUri(pm);
        if (first != null) preview.setImageURI(first);
        root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button btnPick = new Button(this);
        btnPick.setText("Bild auswählen");
        btnPick.setOnClickListener(v -> {
            pendingImageTrack = track;
            pendingImageTs = ts;
            pickImageLauncher.launch(new String[]{"image/*"});
        });
        root.addView(btnPick);

        Button btnCamera = new Button(this);
        btnCamera.setText("Foto aufnehmen");
        btnCamera.setOnClickListener(v -> {
            try {
                File photo = createImageFile();
                Uri uri = FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        photo
                );

                pendingImageTrack = track;
                pendingImageTs = ts;
                pendingCameraUri = uri;

                takePictureLauncher.launch(uri);
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Kamera-Fehler", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnCamera);

        Button btnRemoveImages = new Button(this);
        btnRemoveImages.setText("Bilder entfernen");
        btnRemoveImages.setOnClickListener(v -> {
            pm.imageUris.clear();
            tvImages.setText(imageSummary(pm));
            preview.setImageDrawable(null);
        });
        root.addView(btnRemoveImages);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Marker bearbeiten");
        builder.setView(root);

        builder.setPositiveButton("Speichern", (d, w) -> {
            pm.comment = etComment.getText().toString();
            meta.put(ts, pm);
            saveMetaForTrack(track, meta);
            loadAllTracksAndUpdateMap();
        });

        builder.setNegativeButton("Abbrechen", null);
        builder.show();
    }

    private String imageSummary(PointMeta pm) {
        int n = (pm.imageUris == null) ? 0 : pm.imageUris.size();
        return "Bilder: " + n;
    }

    private Uri firstImageUri(PointMeta pm) {
        if (pm.imageUris == null || pm.imageUris.isEmpty()) return null;
        try {
            return Uri.parse(pm.imageUris.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir == null) storageDir = getFilesDir();
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    private File metaFileForTrack(TrackInfo t) {
        return new File(getFilesDir(), t.filename + ".meta.json");
    }

    private Map<String, PointMeta> getMetaMap(TrackInfo t) {
        if (metaCache.containsKey(t.filename)) {
            return metaCache.get(t.filename);
        }
        Map<String, PointMeta> loaded = loadMetaForTrack(t);
        metaCache.put(t.filename, loaded);
        return loaded;
    }

    private Map<String, PointMeta> loadMetaForTrack(TrackInfo t) {
        File f = metaFileForTrack(t);
        if (!f.exists()) return new HashMap<>();
        try (FileInputStream fis = openFileInput(f.getName());
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            Type type = new TypeToken<Map<String, PointMeta>>() {}.getType();
            Map<String, PointMeta> map = gson.fromJson(sb.toString(), type);
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void saveMetaForTrack(TrackInfo t, Map<String, PointMeta> meta) {
        File f = metaFileForTrack(t);
        String json = gson.toJson(meta);
        try (FileOutputStream fos = openFileOutput(f.getName(), MODE_PRIVATE)) {
            fos.write(json.getBytes());
        } catch (Exception ignored) { }
        metaCache.put(t.filename, meta);
    }

    private void shareCsvFile(String filename) {
        File file = new File(getFilesDir(), filename);

        if (!file.exists()) {
            Toast.makeText(this, "CSV-Datei nicht gefunden", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(intent, "CSV-Datei teilen"));
    }

    // --- Keyboard helper ---
    private void hideKeyboard(View v) {
        if (v == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void showCreateTrackDialog() {
        String[] colorNames = {"Rot", "Grün", "Blau", "Orange", "Lila"};
        int[] colorValues = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFF8800, 0xFFAA00FF};

        final int[] selectedIndex = {0};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        Context themedCtx = builder.getContext();

        // --- Custom Title oben + Button (wird nicht von Tastatur verdeckt) ---
        LinearLayout titleBar = new LinearLayout(themedCtx);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        titleBar.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(themedCtx);
        tvTitle.setText("Neuen Track erstellen");
        tvTitle.setTextSize(18f);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnHideKb = new Button(themedCtx);
        btnHideKb.setText("Tastatur zu");

        titleBar.addView(tvTitle);
        titleBar.addView(btnHideKb);

        builder.setCustomTitle(titleBar);

        // --- Inhalt ---
        final EditText input = new EditText(themedCtx);
        input.setHint("Track-Name");
        builder.setView(input);

        // "Done" Taste auf der Tastatur + Listener, der NUR Tastatur schließt
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean isDone = actionId == EditorInfo.IME_ACTION_DONE;
            boolean isEnter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;

            if (isDone || isEnter) {
                hideKeyboard(input);
                input.clearFocus();
                return true;
            }
            return false;
        });

        builder.setSingleChoiceItems(colorNames, 0, (dialog, which) -> selectedIndex[0] = which);

        // Buttons unten (OK/Abbrechen) ohne Auto-Dismiss (für Validierung)
        builder.setPositiveButton("OK", null);
        builder.setNegativeButton("Abbrechen", null);

        AlertDialog dialog = builder.create();

        dialog.setOnShowListener(d -> {
            btnHideKb.setOnClickListener(v -> {
                hideKeyboard(input);
                input.clearFocus();
            });

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name darf nicht leer sein", Toast.LENGTH_SHORT).show();
                    return;
                }

                hideKeyboard(input);

                String filename = "track_" + name.replaceAll("\\s+", "_") + ".csv";
                int color = colorValues[selectedIndex[0]];
                TrackInfo t = new TrackInfo(name, filename, color);
                tracks.add(t);

                boolean[] newVisible = new boolean[tracks.size()];
                System.arraycopy(visibleTracks, 0, newVisible, 0, visibleTracks.length);
                newVisible[tracks.size() - 1] = true;
                visibleTracks = newVisible;

                currentTrack = t;
                saveAllTrackPrefs();

                File f = new File(getFilesDir(), filename);
                ensureCsvHasHeader(f);
                loadAllTracksAndUpdateMap();

                dialog.dismiss();
            });

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                hideKeyboard(input);
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void showSelectCurrentTrackDialog() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Keine Tracks vorhanden", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[tracks.size()];
        int checked = -1;
        for (int i = 0; i < tracks.size(); i++) {
            names[i] = tracks.get(i).name;
            if (currentTrack != null && tracks.get(i).name.equals(currentTrack.name)) {
                checked = i;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Aktiven Track wählen");
        builder.setSingleChoiceItems(names, checked, (dialog, which) -> currentTrack = tracks.get(which));
        builder.setPositiveButton("OK", (d, w) -> saveCurrentTrackToPrefs());
        builder.setNegativeButton("Abbrechen", null);
        builder.show();
    }

    private void showTrackVisibilityDialog() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Keine Tracks vorhanden", Toast.LENGTH_SHORT).show();
            return;
        }

        if (visibleTracks.length != tracks.size()) {
            visibleTracks = new boolean[tracks.size()];
            for (int i = 0; i < visibleTracks.length; i++) visibleTracks[i] = true;
        }

        String[] names = new String[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) names[i] = tracks.get(i).name;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Tracks anzeigen");
        builder.setMultiChoiceItems(names, visibleTracks, (dialog, which, isChecked) -> visibleTracks[which] = isChecked);
        builder.setPositiveButton("OK", (d, w) -> {
            saveVisibleToPrefs();
            loadAllTracksAndUpdateMap();
        });
        builder.setNegativeButton("Abbrechen", null);
        builder.show();
    }

    private void showExportDialog() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Keine Tracks vorhanden", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) names[i] = tracks.get(i).name;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Track zum Export wählen");
        builder.setItems(names, (dialog, which) -> shareCsvFile(tracks.get(which).filename));
        builder.show();
    }

    private void showDeleteTrackDialog() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Keine Tracks vorhanden", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) names[i] = tracks.get(i).name;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Track löschen");
        builder.setItems(names, (dialog, which) -> {
            TrackInfo t = tracks.get(which);
            deleteFile(t.filename);
            File meta = metaFileForTrack(t);
            if (meta.exists()) deleteFile(meta.getName());
            metaCache.remove(t.filename);

            tracks.remove(which);

            boolean[] newVisible = new boolean[tracks.size()];
            for (int i = 0; i < newVisible.length; i++) newVisible[i] = i < visibleTracks.length && visibleTracks[i];
            visibleTracks = newVisible;

            if (currentTrack != null && currentTrack.name.equals(t.name)) {
                currentTrack = tracks.isEmpty() ? null : tracks.get(0);
            }
            saveAllTrackPrefs();
            loadAllTracksAndUpdateMap();
        });
        builder.show();
    }

    private void loadTracksFromPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String json = prefs.getString(PREF_TRACKS, "[]");
        Type type = new TypeToken<List<TrackInfo>>() {}.getType();
        List<TrackInfo> list = gson.fromJson(json, type);
        tracks.clear();
        if (list != null) tracks.addAll(list);
    }

    private void saveTracksToPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor ed = prefs.edit();
        String json = gson.toJson(tracks);
        ed.putString(PREF_TRACKS, json);
        ed.apply();
    }

    private void loadVisibleFromPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String json = prefs.getString(PREF_VISIBLE, "");
        if (json.isEmpty()) {
            visibleTracks = new boolean[tracks.size()];
            for (int i = 0; i < visibleTracks.length; i++) visibleTracks[i] = true;
        } else {
            boolean[] arr = gson.fromJson(json, boolean[].class);
            if (arr != null && arr.length == tracks.size()) {
                visibleTracks = arr;
            } else {
                visibleTracks = new boolean[tracks.size()];
                for (int i = 0; i < visibleTracks.length; i++) visibleTracks[i] = true;
            }
        }
    }

    private void saveVisibleToPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor ed = prefs.edit();
        String json = gson.toJson(visibleTracks);
        ed.putString(PREF_VISIBLE, json);
        ed.apply();
    }

    private void loadCurrentTrackFromPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String name = prefs.getString(PREF_CURRENT, null);
        if (name == null) return;
        for (TrackInfo t : tracks) {
            if (t.name.equals(name)) {
                currentTrack = t;
                break;
            }
        }
    }

    private void saveCurrentTrackToPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(PREF_CURRENT, currentTrack != null ? currentTrack.name : null);
        ed.apply();
        saveTracksToPrefs();
    }

    private void loadContinuousModeFromPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        continuousMode = prefs.getBoolean(PREF_CONTINUOUS_MODE, false);
    }

    private void saveContinuousModeToPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean(PREF_CONTINUOUS_MODE, continuousMode).apply();
    }

    private void saveAllTrackPrefs() {
        saveTracksToPrefs();
        saveVisibleToPrefs();
        saveCurrentTrackToPrefs();
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(continuousMode ? 2000 : 1000);
        locationRequest.setFastestInterval(continuousMode ? 1000 : 500);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    private void stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
            loadAllTracksAndUpdateMap();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                textView.setText("Berechtigung zum Standortzugriff verweigert");
            }
        }
    }
}
