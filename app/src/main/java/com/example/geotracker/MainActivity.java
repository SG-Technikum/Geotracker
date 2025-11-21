package com.example.geotracker;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
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
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private static final int PERMISSIONS_REQUEST_LOCATION = 100;
    private static final String PREF_TRACKS = "tracks_json";
    private static final String PREF_VISIBLE = "tracks_visible_json";
    private static final String PREF_CURRENT = "tracks_current_name";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private TextView textView;
    private MapView map;

    private DrawerLayout drawerLayout;
    private ActionBarDrawerToggle drawerToggle;

    // Track-Modell
    public static class TrackInfo {
        String name;      // Anzeigename
        String filename;  // Dateiname im internen Speicher
        int color;        // ARGB-Farbe

        public TrackInfo(String name, String filename, int color) {
            this.name = name;
            this.filename = filename;
            this.color = color;
        }
    }

    private final List<TrackInfo> tracks = new ArrayList<>();
    private boolean[] visibleTracks = new boolean[0];
    private TrackInfo currentTrack = null;

    private final Gson gson = new Gson();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Toolbar + Drawer
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

        // OSMDroid Konfiguration
        Context ctx = getApplicationContext();
        org.osmdroid.config.Configuration.getInstance()
                .load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));

        textView = findViewById(R.id.tv_active_geo);
        map = findViewById(R.id.map);
        map.setMultiTouchControls(true);

        // Buttons
        Button btnSaveLocation = findViewById(R.id.btn_save_location);
        btnSaveLocation.setOnClickListener(v -> {
            saveLocationToCSV();
            loadAllTracksAndUpdateMap();
        });

        Button btnUpdateMap = findViewById(R.id.btn_update_map);
        btnUpdateMap.setOnClickListener(v -> {
            loadAllTracksAndUpdateMap();
            Toast.makeText(this, "Karte aktualisiert", Toast.LENGTH_SHORT).show();
        });

        Button btnShareCsv = findViewById(R.id.btn_share_csv);
        btnShareCsv.setOnClickListener(v -> showExportDialog());

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
                                "\nLongitude: " + location.getLongitude();
                        textView.setText(coords);
                    }
                }
            }
        };

        // Tracks + Einstellungen laden
        loadTracksFromPrefs();
        loadVisibleFromPrefs();
        loadCurrentTrackFromPrefs();

        // falls keine Tracks existieren, einen Standard-Track anlegen
        if (tracks.isEmpty()) {
            TrackInfo t = new TrackInfo("Standard", "track_standard.csv", 0xFF0000FF);
            tracks.add(t);
            currentTrack = t;
            visibleTracks = new boolean[]{true};
            saveAllTrackPrefs();
            ensureCsvHasHeader(new File(getFilesDir(), t.filename));
        }

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

    // Toolbar-Menü (falls du z.B. Track-Aktionen im Toolbar-Menü willst)
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    // Drawer Navigation
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
        }

        drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
        return true;
    }

    // ---------- CSV + Tracks ----------

    private void ensureCsvHasHeader(File file) {
        if (!file.exists()) {
            try (FileOutputStream fos = openFileOutput(file.getName(), MODE_PRIVATE)) {
                String header = "Timestamp,Latitude,Longitude\n";
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
            String row = timestamp + "," + lat + "," + lon + "\n";

            try (FileOutputStream fos = openFileOutput(currentTrack.filename, MODE_APPEND)) {
                fos.write(row.getBytes());
                Toast.makeText(this, "Koordinaten gespeichert", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Fehler beim Speichern", Toast.LENGTH_SHORT).show();
        }
    }

    private List<GeoPoint> loadPointsFromCsv(String filename) {
        List<GeoPoint> result = new ArrayList<>();
        try (FileInputStream fis = openFileInput(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { // Header überspringen
                    first = false;
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    double lat = Double.parseDouble(parts[1].trim());
                    double lon = Double.parseDouble(parts[2].trim());
                    result.add(new GeoPoint(lat, lon));
                }
            }
        } catch (Exception e) {
            // Datei evtl. noch nicht vorhanden -> ignorieren
        }
        return result;
    }

    private void loadAllTracksAndUpdateMap() {
        map.getOverlays().clear();

        for (int i = 0; i < tracks.size(); i++) {
            if (visibleTracks.length <= i || !visibleTracks[i]) continue;

            TrackInfo t = tracks.get(i);
            List<GeoPoint> points = loadPointsFromCsv(t.filename);
            if (points.isEmpty()) continue;

            // Marker
            for (GeoPoint p : points) {
                Marker m = new Marker(map);
                m.setPosition(p);
                m.setTitle(t.name);
                // Option: Standard-Icon in Farbe t.color einfärben; hier nutzen wir Standardmarker
                map.getOverlays().add(m);
            }

            // Linie
            if (points.size() > 1) {
                Polyline line = new Polyline(map);
                line.setPoints(points);
                line.setColor(t.color);
                line.setWidth(15f);
                map.getOverlays().add(line);
            }
        }

        // Auf letzten Punkt des aktuellen Tracks zentrieren
        if (currentTrack != null) {
            List<GeoPoint> pts = loadPointsFromCsv(currentTrack.filename);
            if (!pts.isEmpty()) {
                map.getController().setCenter(pts.get(pts.size() - 1));
                map.getController().setZoom(15);
            }
        }

        map.invalidate();
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

    // ---------- Dialoge: Tracks verwalten ----------

    private void showCreateTrackDialog() {
        String[] colorNames = {"Rot", "Grün", "Blau", "Orange", "Lila"};
        int[] colorValues = {
                0xFFFF0000,
                0xFF00FF00,
                0xFF0000FF,
                0xFFFF8800,
                0xFFAA00FF
        };

        final int[] selectedIndex = {0};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Neuen Track erstellen");

        // Name-Eingabe
        final EditText input = new EditText(this);
        input.setHint("Track-Name");
        builder.setView(input);

        builder.setSingleChoiceItems(colorNames, 0, (dialog, which) -> selectedIndex[0] = which);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Name darf nicht leer sein", Toast.LENGTH_SHORT).show();
                return;
            }
            String filename = "track_" + name.replaceAll("\\s+", "_") + ".csv";
            int color = colorValues[selectedIndex[0]];
            TrackInfo t = new TrackInfo(name, filename, color);
            tracks.add(t);

            // Sichtbarkeit erweitern
            boolean[] newVisible = new boolean[tracks.size()];
            System.arraycopy(visibleTracks, 0, newVisible, 0, visibleTracks.length);
            newVisible[tracks.size() - 1] = true;
            visibleTracks = newVisible;

            currentTrack = t;
            saveAllTrackPrefs();

            File f = new File(getFilesDir(), filename);
            ensureCsvHasHeader(f);
            loadAllTracksAndUpdateMap();
        });

        builder.setNegativeButton("Abbrechen", null);
        builder.show();
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
        builder.setSingleChoiceItems(names, checked, (dialog, which) -> {
            currentTrack = tracks.get(which);
        });
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
        builder.setMultiChoiceItems(names, visibleTracks, (dialog, which, isChecked) -> {
            visibleTracks[which] = isChecked;
        });
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
            tracks.remove(which);

            // Sichtbarkeit anpassen
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

    // ---------- SharedPreferences für Tracks ----------

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

    private void saveAllTrackPrefs() {
        saveTracksToPrefs();
        saveVisibleToPrefs();
        saveCurrentTrackToPrefs();
    }

    // ---------- Location Lifecycle ----------

    private void startLocationUpdates() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(1000);
        locationRequest.setFastestInterval(500);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        fusedLocationClient.requestLocationUpdates(locationRequest,
                locationCallback,
                null);
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
