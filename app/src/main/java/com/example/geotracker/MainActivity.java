package com.example.geotracker;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import org.osmdroid.views.overlay.mylocation.DirectedLocationOverlay;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, SensorEventListener {

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

    // Standort overlay (Kreis + Pfeil)
    private DirectedLocationOverlay myLocationOverlay;
    private Location lastLocation = null;

    // Kompass / Blickrichtung
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private float lastAzimuthDeg = Float.NaN;

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

    // CSV Record
    static class PointRecord {
        String ts;
        double lat;
        double lon;
        String mode; // MANUAL / CONTINUOUS / HIGHLIGHT
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

    // Marker-Icon Cache pro Farbe
    private final Map<Integer, android.graphics.drawable.Drawable> markerIconCache = new HashMap<>();

    // Bild-Picker / Kamera
    private ActivityResultLauncher<String[]> pickImageLauncher;
    private ActivityResultLauncher<Uri> takePictureBackLauncher;

    private TrackInfo pendingImageTrack = null;
    private String pendingImageTs = null;
    private Uri pendingCameraUri = null;

    // Aktiver Marker-Edit-Dialog (für Live-Preview Updates)
    private AlertDialog activeMarkerDialog = null;
    private TrackInfo activeEditTrack = null;
    private String activeEditTs = null;
    private TextView activeImagesText = null;
    private ImageView activePreview = null;
    private EditText activeEtComment = null;

    /**
     * Custom Contract: ACTION_IMAGE_CAPTURE mit "Back camera" Hints + sauberen URI-Permissions.
     * (Nicht garantiert, aber best-effort mit externer Kamera-App.)
     */
    public static class TakePicturePreferBackCamera extends ActivityResultContract<Uri, Boolean> {

        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, Uri input) {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, input);

            // Wichtig: URI Permissions explizit setzen (damit die Kamera wirklich schreiben kann)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("photo", input));

            // Back-Camera Hints (werden von manchen Kamera-Apps beachtet, von manchen ignoriert)
            intent.putExtra("android.intent.extras.CAMERA_FACING", Camera.CameraInfo.CAMERA_FACING_BACK);
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", false);
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 0);
            intent.putExtra("android.intent.extras.LENS_FACING_BACK", 1);

            // URI Permission an alle möglichen Kamera-Activities grant-en
            List<ResolveInfo> resInfoList = context.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

            for (ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                context.grantUriPermission(packageName, input,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            return intent;
        }

        @Override
        public Boolean parseResult(int resultCode, @Nullable Intent intent) {
            return resultCode == Activity.RESULT_OK;
        }
    }

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

        // Standort-Overlay (Kreis + Pfeil)
        myLocationOverlay = new DirectedLocationOverlay(this);
        myLocationOverlay.setShowAccuracy(true);

        initActivityResultLaunchers();

        Button btnSaveLocation = findViewById(R.id.btn_save_location);
        btnSaveLocation.setOnClickListener(v -> {
            if (continuousMode) addHighlightPoint();
            else saveLocationToCSV();
            loadAllTracksAndUpdateMap();
        });

        Button btnUpdateMap = findViewById(R.id.btn_update_map);
        btnUpdateMap.setOnClickListener(v -> {
            loadAllTracksAndUpdateMap();
            Toast.makeText(this, "Karte aktualisiert", Toast.LENGTH_SHORT).show();
        });

        // "Exportieren": ZIP (CSV + Bilder)
        Button btnShareCsv = findViewById(R.id.btn_share_csv);
        btnShareCsv.setOnClickListener(v -> showExportDialog());

        btnToggleContinuous = findViewById(R.id.btn_toggle_continuous);
        btnToggleContinuous.setOnClickListener(v -> toggleContinuousMode());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Sensor für Blickrichtung (Rotation Vector)
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    textView.setText("Standort nicht verfügbar");
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    if (location == null) continue;

                    lastLocation = location;

                    String coords = "Latitude: " + location.getLatitude() +
                            "\nLongitude: " + location.getLongitude() +
                            (continuousMode ? "\n[CONTINUOUS MODE]" : "");
                    textView.setText(coords);

                    updateMyLocationOverlay();

                    if (continuousMode && currentTrack != null) {
                        saveLocationContinuous(location);
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
        } else {
            for (TrackInfo t : tracks) ensureCsvHasHeader(new File(getFilesDir(), t.filename));
            if (currentTrack == null && !tracks.isEmpty()) currentTrack = tracks.get(0);
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
                    // Pending sofort "leeren", damit es nie hängen bleibt (wichtig fürs "erst beim 2. Mal" Problem)
                    final TrackInfo track = pendingImageTrack;
                    final String ts = pendingImageTs;
                    pendingImageTrack = null;
                    pendingImageTs = null;

                    if (uri == null || track == null || ts == null) return;

                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (Exception ignored) { }

                    Map<String, PointMeta> meta = getMetaMap(track);
                    PointMeta pm = meta.getOrDefault(ts, new PointMeta());
                    pm.imageUris.add(uri.toString());
                    meta.put(ts, pm);
                    saveMetaForTrack(track, meta);

                    loadAllTracksAndUpdateMap();
                    refreshActiveMarkerDialogUiIfOpen();
                }
        );

        takePictureBackLauncher = registerForActivityResult(
                new TakePicturePreferBackCamera(),
                success -> {
                    // Pending sofort "leeren", damit es nie hängen bleibt
                    final TrackInfo track = pendingImageTrack;
                    final String ts = pendingImageTs;
                    final Uri photoUri = pendingCameraUri;
                    pendingImageTrack = null;
                    pendingImageTs = null;
                    pendingCameraUri = null;

                    if (!success || photoUri == null || track == null || ts == null) return;

                    Map<String, PointMeta> meta = getMetaMap(track);
                    PointMeta pm = meta.getOrDefault(ts, new PointMeta());
                    pm.imageUris.add(photoUri.toString());
                    meta.put(ts, pm);
                    saveMetaForTrack(track, meta);

                    loadAllTracksAndUpdateMap();
                    refreshActiveMarkerDialogUiIfOpen();
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
            String row = timestamp + "," + lat + "," + lon + ",HIGHLIGHT\n";

            try (FileOutputStream fos = openFileOutput(currentTrack.filename, MODE_APPEND)) {
                fos.write(row.getBytes(StandardCharsets.UTF_8));
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
            String row = timestamp + "," + location.getLatitude() + "," + location.getLongitude() + ",CONTINUOUS\n";

            try (FileOutputStream fos = openFileOutput(currentTrack.filename, MODE_APPEND)) {
                fos.write(row.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void ensureCsvHasHeader(File file) {
        if (!file.exists() || file.length() == 0) {
            try (FileOutputStream fos = openFileOutput(file.getName(), MODE_PRIVATE)) {
                String header = "Timestamp,Latitude,Longitude,Mode\n";
                fos.write(header.getBytes(StandardCharsets.UTF_8));
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
            String row = timestamp + "," + lat + "," + lon + ",MANUAL\n";

            try (FileOutputStream fos = openFileOutput(currentTrack.filename, MODE_APPEND)) {
                fos.write(row.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Koordinaten gespeichert", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Fehler beim Speichern", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Liest sowohl:
     * - NEU: Timestamp,Latitude,Longitude,Mode
     * - ALT: Timestamp,Type,Latitude,Longitude
     */
    private List<PointRecord> loadRecordsFromCsv(String filename) {
        List<PointRecord> result = new ArrayList<>();
        try (FileInputStream fis = openFileInput(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) return result;

            String[] headers = headerLine.split(",");
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                idx.put(headers[i].trim().toLowerCase(Locale.US), i);
            }

            int iTs = idx.getOrDefault("timestamp", 0);

            // Neu
            int iLatN = idx.getOrDefault("latitude", -1);
            int iLonN = idx.getOrDefault("longitude", -1);
            int iModeN = idx.getOrDefault("mode", -1);

            // Alt
            int iTypeO = idx.getOrDefault("type", 1);
            int iLatO = idx.getOrDefault("latitude", 2);
            int iLonO = idx.getOrDefault("longitude", 3);

            boolean newFormat = (iLatN >= 0 && iLonN >= 0 && iModeN >= 0);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", -1);

                try {
                    PointRecord r = new PointRecord();
                    if (newFormat) {
                        int need = Math.max(Math.max(iTs, iLatN), Math.max(iLonN, iModeN));
                        if (parts.length <= need) continue;
                        r.ts = parts[iTs].trim();
                        r.lat = Double.parseDouble(parts[iLatN].trim());
                        r.lon = Double.parseDouble(parts[iLonN].trim());
                        r.mode = parts[iModeN].trim();
                    } else {
                        if (parts.length < 4) continue;
                        r.ts = parts[0].trim();
                        r.mode = parts[iTypeO].trim();
                        r.lat = Double.parseDouble(parts[iLatO].trim());
                        r.lon = Double.parseDouble(parts[iLonO].trim());
                    }
                    result.add(r);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return result;
    }

    private List<PointRecord> filterHighlight(List<PointRecord> all) {
        List<PointRecord> res = new ArrayList<>();
        for (PointRecord r : all) {
            if ("HIGHLIGHT".equals(r.mode)) res.add(r);
        }
        return res;
    }

    // CONTINUOUS dauerhaft auf Karte ausblenden (Marker + Polyline)
    private List<PointRecord> filterOutContinuous(List<PointRecord> all) {
        List<PointRecord> res = new ArrayList<>();
        for (PointRecord r : all) {
            if (!"CONTINUOUS".equals(r.mode)) res.add(r);
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

            // Karte: CONTINUOUS nie anzeigen
            List<PointRecord> visibleRecords = filterOutContinuous(allRecords);

            // Marker:
            // - Continuous Mode: nur Highlights
            // - sonst: MANUAL + HIGHLIGHT
            List<PointRecord> markerRecords = continuousMode
                    ? filterHighlight(visibleRecords)
                    : visibleRecords;

            Map<String, PointMeta> meta = getMetaMap(t);
            android.graphics.drawable.Drawable icon = getColoredMarkerIcon(t.color);

            for (PointRecord r : markerRecords) {
                Marker m = new Marker(map);
                m.setPosition(new GeoPoint(r.lat, r.lon));

                // Wichtig: setIcon vor setAnchor
                m.setIcon(icon);
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

                String baseTitle = t.name + ("HIGHLIGHT".equals(r.mode) ? " (Highlight)" : "");
                m.setTitle(baseTitle);

                PointMeta pm = meta.get(r.ts);
                if (pm != null && pm.comment != null && !pm.comment.trim().isEmpty()) {
                    m.setSnippet(pm.comment.trim());
                } else {
                    m.setSnippet("");
                }

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

            // Polyline ebenfalls nur aus sichtbaren Punkten (ohne Continuous)
            List<GeoPoint> pts = toGeoPoints(visibleRecords);
            if (pts.size() > 1) {
                Polyline line = new Polyline(map);
                line.setPoints(pts);
                line.setColor(t.color);
                line.setWidth(continuousMode ? 8f : 15f);
                map.getOverlays().add(line);
            }
        }

        // Standort-Overlay immer oben drüber
        if (myLocationOverlay != null) {
            map.getOverlays().add(myLocationOverlay);
        }

        // Center auf letzten sichtbaren Punkt
        if (currentTrack != null) {
            List<PointRecord> all = loadRecordsFromCsv(currentTrack.filename);
            List<PointRecord> visible = filterOutContinuous(all);
            if (!visible.isEmpty()) {
                PointRecord last = visible.get(visible.size() - 1);
                map.getController().setCenter(new GeoPoint(last.lat, last.lon));
                map.getController().setZoom(continuousMode ? 18 : 15);
            }
        }

        map.invalidate();
    }

    private android.graphics.drawable.Drawable getColoredMarkerIcon(int color) {
        android.graphics.drawable.Drawable cached = markerIconCache.get(color);
        if (cached != null) return cached;

        float density = getResources().getDisplayMetrics().density;
        int size = (int) (22 * density);
        int stroke = (int) (2 * density);

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);

        android.graphics.Paint pFill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        pFill.setStyle(android.graphics.Paint.Style.FILL);
        pFill.setColor(color);

        android.graphics.Paint pStroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        pStroke.setStyle(android.graphics.Paint.Style.STROKE);
        pStroke.setStrokeWidth(stroke);
        pStroke.setColor(0xFFFFFFFF);

        float cx = size / 2f;
        float cy = size / 2f;
        float r = (size / 2f) - stroke;

        c.drawCircle(cx, cy, r, pFill);
        c.drawCircle(cx, cy, r, pStroke);

        android.graphics.drawable.Drawable d = new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
        markerIconCache.put(color, d);
        return d;
    }

    private TrackInfo findTrackByFilename(String filename) {
        for (TrackInfo t : tracks) {
            if (t.filename.equals(filename)) return t;
        }
        return null;
    }

    // --- Standort Overlay Update (Kreis + Pfeil) ---
    private void updateMyLocationOverlay() {
        if (myLocationOverlay == null || lastLocation == null) return;

        myLocationOverlay.setLocation(new GeoPoint(lastLocation.getLatitude(), lastLocation.getLongitude()));

        int acc = (int) lastLocation.getAccuracy();
        if (acc <= 0) acc = 0;
        myLocationOverlay.setAccuracy(acc);

        // Pfeil: Blickrichtung (Kompass). Fallback: GPS bearing, wenn vorhanden.
        float bearing;
        if (!Float.isNaN(lastAzimuthDeg)) bearing = lastAzimuthDeg;
        else if (lastLocation.hasBearing()) bearing = lastLocation.getBearing();
        else bearing = 0f;

        myLocationOverlay.setBearing(bearing);
        map.postInvalidate();
    }

    // --- SensorEventListener (Blickrichtung) ---
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;

        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);

        // Display-Rotation berücksichtigen
        int rotation = Surface.ROTATION_0;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            rotation = wm.getDefaultDisplay().getRotation();
        }

        float[] adjustedMatrix = new float[9];
        switch (rotation) {
            case Surface.ROTATION_90:
                SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjustedMatrix);
                break;
            case Surface.ROTATION_180:
                SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjustedMatrix);
                break;
            case Surface.ROTATION_270:
                SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjustedMatrix);
                break;
            case Surface.ROTATION_0:
            default:
                adjustedMatrix = rotationMatrix;
                break;
        }

        SensorManager.getOrientation(adjustedMatrix, orientation);

        float azimuthRad = orientation[0];
        float azimuthDeg = (float) Math.toDegrees(azimuthRad);
        azimuthDeg = (azimuthDeg + 360f) % 360f;

        // Entprellen: nur updaten wenn sich etwas ändert
        if (Float.isNaN(lastAzimuthDeg) || Math.abs(azimuthDeg - lastAzimuthDeg) > 1.5f) {
            lastAzimuthDeg = azimuthDeg;
            updateMyLocationOverlay();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ---------- Keyboard Helper (Enter schließt Tastatur) ----------

    private void installEnterClosesKeyboard(EditText et, boolean singleLine) {
        if (et == null) return;
        et.setSingleLine(singleLine);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setOnEditorActionListener((v, actionId, event) -> {
            boolean isDone = actionId == EditorInfo.IME_ACTION_DONE;
            boolean isEnter = (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);

            if (isDone || isEnter) {
                hideKeyboard(et);
                et.clearFocus();
                return true;
            }
            return false;
        });
    }

    private void hideKeyboard(View v) {
        if (v == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---------- Marker-Edit Dialog (Kommentar + Bilder) ----------

    private void refreshActiveMarkerDialogUiIfOpen() {
        if (activeMarkerDialog == null || !activeMarkerDialog.isShowing()) return;
        if (activeEditTrack == null || activeEditTs == null) return;
        if (activeImagesText == null || activePreview == null) return;

        Map<String, PointMeta> meta = getMetaMap(activeEditTrack);
        PointMeta pm = meta.getOrDefault(activeEditTs, new PointMeta());

        activeImagesText.setText(imageSummary(pm));
        Uri first = firstImageUri(pm);
        if (first != null) activePreview.setImageURI(first);
        else activePreview.setImageDrawable(null);
    }

    private void showMarkerEditDialog(TrackInfo track, String ts) {
        Map<String, PointMeta> meta = getMetaMap(track);
        PointMeta pm = meta.getOrDefault(ts, new PointMeta());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        EditText etComment = new EditText(this);
        etComment.setHint("Kommentar");
        etComment.setText(pm.comment != null ? pm.comment : "");
        // Anforderung: Enter schließt die Tastatur -> singleLine=true (kein Zeilenumbruch)
        installEnterClosesKeyboard(etComment, true);
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
        btnCamera.setText("Foto aufnehmen (Rückkamera)");
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

                takePictureBackLauncher.launch(uri);
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Kamera-Fehler", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnCamera);

        Button btnRemoveImages = new Button(this);
        btnRemoveImages.setText("Bilder entfernen");
        btnRemoveImages.setOnClickListener(v -> {
            // Jetzt: sofort persistieren (nicht erst nach "Speichern")
            Map<String, PointMeta> meta2 = getMetaMap(track);
            PointMeta pm2 = meta2.getOrDefault(ts, new PointMeta());
            pm2.imageUris.clear();
            meta2.put(ts, pm2);
            saveMetaForTrack(track, meta2);

            tvImages.setText(imageSummary(pm2));
            preview.setImageDrawable(null);
            loadAllTracksAndUpdateMap();
        });
        root.addView(btnRemoveImages);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Marker bearbeiten");
        builder.setView(root);

        builder.setPositiveButton("Speichern", (d, w) -> {
            Map<String, PointMeta> meta3 = getMetaMap(track);
            PointMeta pm3 = meta3.getOrDefault(ts, new PointMeta());
            pm3.comment = etComment.getText().toString();
            meta3.put(ts, pm3);
            saveMetaForTrack(track, meta3);
            loadAllTracksAndUpdateMap();
        });

        builder.setNegativeButton("Abbrechen", null);

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            // Dialog-Refs zurücksetzen
            activeMarkerDialog = null;
            activeEditTrack = null;
            activeEditTs = null;
            activeImagesText = null;
            activePreview = null;
            activeEtComment = null;
        });
        dialog.show();

        // Aktiven Dialog merken (damit TakePicture/OpenDocument Callback die UI sofort aktualisieren kann)
        activeMarkerDialog = dialog;
        activeEditTrack = track;
        activeEditTs = ts;
        activeImagesText = tvImages;
        activePreview = preview;
        activeEtComment = etComment;
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

    // ---------- Meta JSON ----------

    private File metaFileForTrack(TrackInfo t) {
        return new File(getFilesDir(), t.filename + ".meta.json");
    }

    private Map<String, PointMeta> getMetaMap(TrackInfo t) {
        if (metaCache.containsKey(t.filename)) return metaCache.get(t.filename);
        Map<String, PointMeta> loaded = loadMetaForTrack(t);
        metaCache.put(t.filename, loaded);
        return loaded;
    }

    private Map<String, PointMeta> loadMetaForTrack(TrackInfo t) {
        File f = metaFileForTrack(t);
        if (!f.exists()) return new HashMap<>();
        try (FileInputStream fis = openFileInput(f.getName());
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {

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
            fos.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        metaCache.put(t.filename, meta);
    }

    // ---------- Export: ZIP (CSV + Bilder) ----------

    private void showExportDialog() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Keine Tracks vorhanden", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) names[i] = tracks.get(i).name;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Track zum Export wählen");
        builder.setItems(names, (dialog, which) -> exportTrackZip(tracks.get(which)));
        builder.show();
    }

    private void exportTrackZip(TrackInfo track) {
        try {
            File zipFile = buildZipForTrack(track);

            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", zipFile
            );

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(intent, "ZIP exportieren"));
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Export fehlgeschlagen: " + e.getClass().getSimpleName() +
                    " / " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private File buildZipForTrack(TrackInfo track) throws IOException {
        // Export enthält ALLE Punkte (MANUAL/CONTINUOUS/HIGHLIGHT)
        List<PointRecord> records = loadRecordsFromCsv(track.filename);
        records.sort((a, b) -> a.ts.compareTo(b.ts));

        Map<String, PointMeta> meta = getMetaMap(track);

        String safeName = (track.name == null ? "track" : track.name).replaceAll("[^a-zA-Z0-9_\\-]+", "_");
        File outZip = new File(getCacheDir(), safeName + "_" + System.currentTimeMillis() + ".zip");

        int imgCounter = 1;

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outZip)))) {

            StringBuilder csv = new StringBuilder();
            csv.append("Timestamp,Latitude,Longitude,Mode,Images\n");

            for (PointRecord r : records) {
                PointMeta pm = meta.get(r.ts);

                List<String> exportedNames = new ArrayList<>();
                if (pm != null && pm.imageUris != null) {
                    for (String uriStr : pm.imageUris) {
                        if (uriStr == null || uriStr.trim().isEmpty()) continue;

                        Uri u;
                        try { u = Uri.parse(uriStr); } catch (Exception ex) { continue; }

                        String ext = guessExtension(u);
                        String baseName = String.format(Locale.US, "%04d%s", imgCounter++, ext);
                        String zipPath = "images/" + baseName;

                        try (InputStream in = getContentResolver().openInputStream(u)) {
                            if (in == null) continue;

                            zos.putNextEntry(new ZipEntry(zipPath));
                            copyStream(in, zos);
                            zos.closeEntry();

                            exportedNames.add(baseName);
                        } catch (Exception ignored) { }
                    }
                }

                String imagesCell = String.join(";", exportedNames);
                csv.append(csvEscape(r.ts)).append(",")
                        .append(r.lat).append(",")
                        .append(r.lon).append(",")
                        .append(csvEscape(r.mode)).append(",")
                        .append(csvEscape(imagesCell)).append("\n");
            }

            zos.putNextEntry(new ZipEntry(safeName + ".csv"));
            zos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        return outZip;
    }

    private String guessExtension(Uri u) {
        try {
            String mime = getContentResolver().getType(u);
            if (mime != null) {
                String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (e != null && !e.isEmpty()) return "." + e;
            }
        } catch (Exception ignored) { }

        String p = u.getPath();
        if (p != null) {
            int dot = p.lastIndexOf('.');
            if (dot >= 0 && dot < p.length() - 1) {
                String ext = p.substring(dot);
                if (ext.length() <= 6) return ext;
            }
        }
        return ".jpg";
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needs = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needs) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ---------- Track Dialoge wie vorher ----------

    private void showCreateTrackDialog() {
        String[] colorNames = {"Rot", "Grün", "Blau", "Orange", "Lila"};
        int[] colorValues = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFF8800, 0xFFAA00FF};

        final int[] selectedIndex = {0};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        Context themedCtx = builder.getContext();

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

        final EditText input = new EditText(themedCtx);
        input.setHint("Track-Name");
        installEnterClosesKeyboard(input, true);
        builder.setView(input);

        builder.setSingleChoiceItems(colorNames, 0, (dialog, which) -> selectedIndex[0] = which);
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

                String filename = "track_" + name.replaceAll("[^a-zA-Z0-9_\\-]+", "_") + ".csv";
                int color = colorValues[selectedIndex[0]];

                TrackInfo t = new TrackInfo(name, filename, color);
                tracks.add(t);

                boolean[] newVisible = new boolean[tracks.size()];
                System.arraycopy(visibleTracks, 0, newVisible, 0, Math.min(visibleTracks.length, newVisible.length));
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
            if (currentTrack != null && tracks.get(i).name.equals(currentTrack.name)) checked = i;
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
            for (int i = 0; i < newVisible.length; i++) {
                newVisible[i] = (i < visibleTracks.length) && visibleTracks[i];
            }
            visibleTracks = newVisible;

            if (currentTrack != null && currentTrack.name.equals(t.name)) {
                currentTrack = tracks.isEmpty() ? null : tracks.get(0);
            }

            saveAllTrackPrefs();
            loadAllTracksAndUpdateMap();
        });
        builder.show();
    }

    // ---------- Prefs ----------

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
        if (json == null || json.isEmpty()) {
            visibleTracks = new boolean[tracks.size()];
            for (int i = 0; i < visibleTracks.length; i++) visibleTracks[i] = true;
        } else {
            boolean[] arr = gson.fromJson(json, boolean[].class);
            if (arr != null && arr.length == tracks.size()) visibleTracks = arr;
            else {
                visibleTracks = new boolean[tracks.size()];
                for (int i = 0; i < visibleTracks.length; i++) visibleTracks[i] = true;
            }
        }
    }

    private void saveVisibleToPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putString(PREF_VISIBLE, gson.toJson(visibleTracks)).apply();
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
        prefs.edit().putString(PREF_CURRENT, currentTrack != null ? currentTrack.name : null).apply();
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

    // ---------- Location updates ----------

    private void startLocationUpdates() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(continuousMode ? 2000 : 1000);
        locationRequest.setFastestInterval(continuousMode ? 1000 : 500);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    private void stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocationUpdates();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
        loadAllTracksAndUpdateMap();
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
