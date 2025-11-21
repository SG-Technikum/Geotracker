package com.example.geotracker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSIONS_REQUEST_LOCATION = 100;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private TextView textView;
    private MapView map;

    private List<GeoPoint> savedPoints = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        Context ctx = getApplicationContext();
        org.osmdroid.config.Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));


        textView = findViewById(R.id.tv_active_geo);
        map = findViewById(R.id.map);
        map.setMultiTouchControls(true);

        Button btnSaveLocation = findViewById(R.id.btn_save_location);
        btnSaveLocation.setOnClickListener(v -> {
            saveLocationToCSV();
            updateMapMarkers();
        });

        Button btnShareCsv = findViewById(R.id.btn_share_csv);
        btnShareCsv.setOnClickListener(v -> shareCsvFile());

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

        // Karte initial laden und Marker setzen
        loadPointsFromCSV();
        updateMapMarkers();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSIONS_REQUEST_LOCATION);
        } else {
            startLocationUpdates();
        }
    }

    private void saveLocationToCSV() {
        String data = textView.getText().toString();
        String filename = "locations.csv";

        try (FileOutputStream fos = openFileOutput(filename, MODE_APPEND)) {
            String timestamp = java.time.LocalDateTime.now().toString();
            String row = timestamp + "," + data.replace("\n", ",") + "\n";
            fos.write(row.getBytes());
            Toast.makeText(this, "Koordinaten gespeichert", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Fehler beim Speichern", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareCsvFile() {
        File file = new File(getFilesDir(), "locations.csv");

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

    private void loadPointsFromCSV() {
        try {
            FileInputStream fis = openFileInput("locations.csv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            String line;
            savedPoints.clear();
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    double lat = Double.parseDouble(parts[1].split(":")[1].trim());
                    double lon = Double.parseDouble(parts[2].split(":")[1].trim());
                    savedPoints.add(new GeoPoint(lat, lon));
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateMapMarkers() {
        map.getOverlays().clear();
        for (GeoPoint point : savedPoints) {
            Marker marker = new Marker(map);
            marker.setPosition(point);
            marker.setTitle("Standort");
            map.getOverlays().add(marker);
        }
        if (!savedPoints.isEmpty()) {
            map.getController().setCenter(savedPoints.get(savedPoints.size() - 1));
            map.getController().setZoom(15);
        }
        map.invalidate();
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
            loadPointsFromCSV();
            updateMapMarkers();
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
