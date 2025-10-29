package com.example.exploradorvisualparanios;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

public class Inicio extends AppCompatActivity {

    private static final long SPLASH_DELAY = 3000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inicio);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(Inicio.this, MainActivity.class);
            startActivity(intent);
            finish();
        }, SPLASH_DELAY);
    }
}
