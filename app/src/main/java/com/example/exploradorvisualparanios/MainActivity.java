package com.example.exploradorvisualparanios;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.common.model.DownloadConditions;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final float CONFIDENCE_THRESHOLD = 0.85f;
    private static final String TAG = "MainActivity";

    private PreviewView previewView;
    private ImageView ivImagen;
    private ImageButton btnCloseImage, btnAbrirGaleria, btnTomarFoto, btnAnalizar, btnCambiarCamara;
    private TextView tvResultados, tvDatoCurioso;
    private CardView cardResultados;

    private ImageLabeler imageLabeler;
    private TextToSpeech tts;
    private Translator translatorEnToEs;
    private Map<String, String> datosCuriosos;

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private boolean isAnalysisRunning = false;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private enum AppState { LIVE_CAMERA, IMAGE_DISPLAY }

    // Control global de etiquetas para no repetir
    private final Map<String, Float> etiquetasMostradas = new HashMap<>();

    // --- ActivityResultLaunchers ---
    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) startCamera();
                else Toast.makeText(this, "¡Necesitamos permiso de la cámara!", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    try {
                        InputImage image = InputImage.fromFilePath(this, uri);
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
                        ivImagen.setImageBitmap(bitmap);
                        setAppState(AppState.IMAGE_DISPLAY);
                        analizarImagenEstatica(image);
                    } catch (IOException e) {
                        Log.e(TAG, "Error al cargar imagen de galería", e);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        ivImagen = findViewById(R.id.ivImagen);
        btnCloseImage = findViewById(R.id.btnCloseImage);
        btnAbrirGaleria = findViewById(R.id.btnAbrirGaleria);
        btnTomarFoto = findViewById(R.id.btnTomarFoto);
        btnAnalizar = findViewById(R.id.btnAnalizar);
        btnCambiarCamara = findViewById(R.id.btnCambiarCamara);
        tvResultados = findViewById(R.id.tvResultados);
        tvDatoCurioso = findViewById(R.id.tvDatoCurioso);
        cardResultados = findViewById(R.id.cardResultados);

        cameraExecutor = Executors.newSingleThreadExecutor();
        tts = new TextToSpeech(this, this);

        ImageLabelerOptions options = new ImageLabelerOptions.Builder()
                .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                .build();
        imageLabeler = ImageLabeling.getClient(options);

        crearMapaDeDatosCuriosos();

        btnAnalizar.setOnClickListener(v -> toggleAnalysis());
        btnTomarFoto.setOnClickListener(v -> takePhoto());
        btnAbrirGaleria.setOnClickListener(v -> openGallery());
        btnCloseImage.setOnClickListener(v -> setAppState(AppState.LIVE_CAMERA));
        btnCambiarCamara.setOnClickListener(v -> cambiarCamara());

        handleCameraPermission();

        TranslatorOptions translatorOptions = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build();

        translatorEnToEs = Translation.getClient(translatorOptions);

        DownloadConditions conditions = new DownloadConditions.Builder()
                .requireWifi()
                .build();

        translatorEnToEs.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Modelo de traducción listo", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Error al descargar modelo de traducción", Toast.LENGTH_SHORT).show());
    }

    private void handleCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error al iniciar cámara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        imageCapture = new ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        try {
            if (isAnalysisRunning) {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);
            } else {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al vincular casos de uso", e);
        }
    }

    private void toggleAnalysis() {
        isAnalysisRunning = !isAnalysisRunning;
        if (isAnalysisRunning) {
            btnAnalizar.setImageResource(android.R.drawable.ic_media_pause);
            tvResultados.setText("Analizando en vivo...");
            etiquetasMostradas.clear(); // reiniciar etiquetas
        } else {
            btnAnalizar.setImageResource(android.R.drawable.ic_media_play);
            tvResultados.setText("Análisis detenido");
        }
        bindCameraUseCases();
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        if (isAnalysisRunning) toggleAnalysis();

        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VisualExplorer");
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions
                .Builder(getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                .build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Uri savedUri = outputFileResults.getSavedUri();
                        Toast.makeText(MainActivity.this, "¡Foto guardada!", Toast.LENGTH_SHORT).show();
                        try {
                            InputImage image = InputImage.fromFilePath(MainActivity.this, savedUri);
                            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), savedUri);
                            ivImagen.setImageBitmap(bitmap);
                            setAppState(AppState.IMAGE_DISPLAY);
                            analizarImagenEstatica(image);
                        } catch (IOException e) {
                            Log.e(TAG, "Error procesando imagen guardada", e);
                        }
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Error al tomar foto", exception);
                    }
                });
    }

    private void openGallery() {
        if (isAnalysisRunning) toggleAnalysis();
        pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void cambiarCamara() {
        if (cameraProvider == null) return;

        RotateAnimation rotate = new RotateAnimation(0, 360,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        rotate.setDuration(400);
        rotate.setInterpolator(new LinearInterpolator());
        btnCambiarCamara.startAnimation(rotate);

        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ?
                CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;

        bindCameraUseCases();
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        @androidx.camera.core.ExperimentalGetImage
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            imageLabeler.process(image)
                    .addOnSuccessListener(labels -> runOnUiThread(() -> actualizarEtiquetasEnVivo(labels)))
                    .addOnFailureListener(e -> Log.e(TAG, "Fallo etiquetado en vivo", e))
                    .addOnCompleteListener(task -> {
                        isProcessing.set(false);
                        imageProxy.close();
                    });
        } else {
            isProcessing.set(false);
            imageProxy.close();
        }
    }

    private void actualizarEtiquetasEnVivo(List<ImageLabel> labels) {
        int maxEtiquetas = 5;
        int contador = 0;
        boolean[] primera = {true};

        for (ImageLabel label : labels) {
            if (contador >= maxEtiquetas) break;

            String textoEnIngles = label.getText();
            float confianza = label.getConfidence();

            if (etiquetasMostradas.containsKey(textoEnIngles)) continue;

            etiquetasMostradas.put(textoEnIngles, confianza);
            contador++;

            translatorEnToEs.translate(textoEnIngles)
                    .addOnSuccessListener(traduccion -> runOnUiThread(() -> {
                        String textoActual = tvResultados.getText().toString();
                        tvResultados.setText(textoActual + traduccion + " (" + Math.round(confianza * 100) + "%)\n");

                        if (primera[0]) {
                            String datoCurioso = datosCuriosos.get(textoEnIngles.toLowerCase());
                            tvDatoCurioso.setText(datoCurioso != null ? datoCurioso : "");
                            tvDatoCurioso.setVisibility(datoCurioso != null ? View.VISIBLE : View.GONE);

                            String mensajeHablar = "¡Veo " + traduccion + "!" + (datoCurioso != null ? " " + datoCurioso : "");
                            hablar(mensajeHablar);

                            primera[0] = false;
                        }
                    }))
                    .addOnFailureListener(e -> Log.e(TAG, "Error traduciendo etiqueta", e));
        }
    }

    private void analizarImagenEstatica(InputImage image) {
        tvResultados.setText("Analizando imagen...");
        tvDatoCurioso.setVisibility(View.GONE);
        imageLabeler.process(image)
                .addOnSuccessListener(labels -> runOnUiThread(() -> mostrarResultadosImagen(labels)))
                .addOnFailureListener(e -> {
                    tvResultados.setText("Error al analizar la imagen.");
                    Log.e(TAG, "Fallo etiquetado imagen estática", e);
                });
    }

    private void mostrarResultadosImagen(List<ImageLabel> labels) {
        tvDatoCurioso.setVisibility(View.GONE);
        if (labels.isEmpty()) {
            tvResultados.setText("¡Uy! No reconozco nada. Intenta de nuevo.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        boolean[] primera = {true};

        for (ImageLabel label : labels) {
            String textoEnIngles = label.getText();
            float confianza = label.getConfidence();

            translatorEnToEs.translate(textoEnIngles)
                    .addOnSuccessListener(traduccion -> runOnUiThread(() -> {
                        sb.append(traduccion).append(" (").append(Math.round(confianza * 100)).append("%)\n");
                        tvResultados.setText(sb.toString());

                        if (primera[0]) {
                            String datoCurioso = datosCuriosos.get(textoEnIngles.toLowerCase());
                            tvDatoCurioso.setText(datoCurioso != null ? datoCurioso : "");
                            tvDatoCurioso.setVisibility(datoCurioso != null ? View.VISIBLE : View.GONE);

                            String mensajeHablar = "¡Veo " + traduccion + "!" + (datoCurioso != null ? " " + datoCurioso : "");
                            hablar(mensajeHablar);
                            primera[0] = false;
                        }
                    }))
                    .addOnFailureListener(e -> Log.e(TAG, "Error traduciendo etiqueta", e));
        }
    }

    private void setAppState(AppState state) {
        if (state == AppState.LIVE_CAMERA) {
            previewView.setVisibility(View.VISIBLE);
            ivImagen.setVisibility(View.GONE);
            btnCloseImage.setVisibility(View.GONE);
            tvResultados.setText("Elige una opción abajo");
            tvDatoCurioso.setVisibility(View.GONE);
        } else {
            previewView.setVisibility(View.GONE);
            ivImagen.setVisibility(View.VISIBLE);
            btnCloseImage.setVisibility(View.VISIBLE);
        }
    }

    private void hablar(String texto) {
        if (tts != null && texto != null && !texto.trim().isEmpty()) {
            if (tts.isSpeaking()) tts.stop();
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "" + System.currentTimeMillis());
        }
    }

    private void crearMapaDeDatosCuriosos() {
        datosCuriosos = new HashMap<>();
        datosCuriosos.put("dog", "¿Sabías que los perros pueden oler cosas que nosotros ni imaginamos?");
        datosCuriosos.put("cat", "¿Sabías que los gatos duermen casi todo el día para guardar energía?");
        datosCuriosos.put("bird", "¿Sabías que los pájaros son familia de los dinosaurios?");
        datosCuriosos.put("fish", "¿Sabías que los peces respiran usando branquias?");
        datosCuriosos.put("car", "¿Sabías que el primer coche no corría más rápido que una persona caminando?");
        datosCuriosos.put("bicycle", "¿Sabías que andar en bici es un súper ejercicio para tus piernas?");
        datosCuriosos.put("sun", "¿Sabías que el Sol es una estrella gigante que nos da luz y calor?");
        datosCuriosos.put("moon", "¿Sabías que la Luna es como el gran farol de la noche?");
        datosCuriosos.put("cloud", "¿Sabías que las nubes están hechas de gotitas de agua muy pequeñas?");
        datosCuriosos.put("book", "¿Sabías que cada libro es una aventura nueva esperando a ser leída?");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Locale spanish = new Locale("spa", "MX");
            int result = tts.setLanguage(spanish);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "El idioma español no está soportado.");
            }
        } else {
            Log.e("TTS", "Falló inicialización TTS.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (translatorEnToEs != null) {
            translatorEnToEs.close();
        }
        cameraExecutor.shutdown();
    }
}
