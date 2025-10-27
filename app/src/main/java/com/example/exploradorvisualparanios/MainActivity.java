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
import android.widget.Button;
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

    private static final float CONFIDENCE_THRESHOLD = 0.7f;
    private static final String TAG = "MainActivity";

    // Vistas
    private PreviewView previewView;
    private ImageView ivImagen;
    private ImageButton btnCloseImage, btnAbrirGaleria, btnTomarFoto, btnAnalizar;
    private TextView tvResultados, tvDatoCurioso;
    private CardView cardResultados;

    // ML Kit y TTS
    private ImageLabeler imageLabeler;
    private TextToSpeech tts;
    private Map<String, String> traducciones;
    private Map<String, String> datosCuriosos;

    // CameraX
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;

    // Control de estado
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private boolean isAnalysisRunning = false;
    private enum AppState { LIVE_CAMERA, IMAGE_DISPLAY }

    // --- ActivityResultLaunchers ---
    // Para permisos
    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "¡Necesitamos permiso de la cámara para funcionar!", Toast.LENGTH_LONG).show();
                }
            });

    // Para la galería de fotos
    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    try {
                        InputImage image = InputImage.fromFilePath(this, uri);
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
                        ivImagen.setImageBitmap(bitmap);
                        setAppState(AppState.IMAGE_DISPLAY); // Cambia al estado de visualización de imagen
                        analizarImagenEstatica(image);
                    } catch (IOException e) {
                        Log.e(TAG, "Error al cargar la imagen de la galería", e);
                    }
                } else {
                    Log.d(TAG, "No se seleccionó ninguna imagen.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- Inicialización de vistas ---
        previewView = findViewById(R.id.previewView);
        ivImagen = findViewById(R.id.ivImagen);
        btnCloseImage = findViewById(R.id.btnCloseImage);
        btnAbrirGaleria = findViewById(R.id.btnAbrirGaleria);
        btnTomarFoto = findViewById(R.id.btnTomarFoto);
        btnAnalizar = findViewById(R.id.btnAnalizar);
        tvResultados = findViewById(R.id.tvResultados);
        tvDatoCurioso = findViewById(R.id.tvDatoCurioso);
        cardResultados = findViewById(R.id.cardResultados);

        // --- Configuración de lógica ---
        cameraExecutor = Executors.newSingleThreadExecutor();
        tts = new TextToSpeech(this, this);

        ImageLabelerOptions options = new ImageLabelerOptions.Builder()
                .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                .build();
        imageLabeler = ImageLabeling.getClient(options);

        crearMapaDeTraducciones();
        crearMapaDeDatosCuriosos();

        // --- Asignación de listeners a los botones ---
        btnAnalizar.setOnClickListener(v -> toggleAnalysis());
        btnTomarFoto.setOnClickListener(v -> takePhoto());
        btnAbrirGaleria.setOnClickListener(v -> openGallery());
        btnCloseImage.setOnClickListener(v -> setAppState(AppState.LIVE_CAMERA));

        handleCameraPermission();
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
                Log.e(TAG, "Error al iniciar la cámara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        cameraProvider.unbindAll(); // Desvincula todo antes de volver a vincular

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // Use case para tomar fotos
        imageCapture = new ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();

        // Use case para análisis en vivo
        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        try {
            if (isAnalysisRunning) {
                // Si el análisis está activo, vincula los 3 casos de uso
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);
            } else {
                // Si no, solo vincula la vista previa y la captura
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al vincular casos de uso", e);
        }
    }

    // --- Lógica de Acciones de Botones ---

    private void toggleAnalysis() {
        isAnalysisRunning = !isAnalysisRunning;
        if (isAnalysisRunning) {
            btnAnalizar.setImageResource(android.R.drawable.ic_media_pause); // Cambia a ícono de pausa
            tvResultados.setText("Analizando en vivo...");
            bindCameraUseCases(); // Re-vincula para añadir el análisis
        } else {
            btnAnalizar.setImageResource(android.R.drawable.ic_media_play); // Cambia a ícono de play
            tvResultados.setText("Análisis detenido");
            isProcessing.set(false);
            bindCameraUseCases(); // Re-vincula para quitar el análisis y ahorrar batería
        }
    }

    // Tomar foto
    private void takePhoto() {
        if (imageCapture == null) return;

        // Detener análisis en vivo si está corriendo para evitar conflictos
        if (isAnalysisRunning) {
            toggleAnalysis();
        }

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

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Uri savedUri = outputFileResults.getSavedUri();
                Toast.makeText(MainActivity.this, "¡Foto guardada!", Toast.LENGTH_SHORT).show();
                try {
                    // Muestra la imagen capturada y analízala
                    InputImage image = InputImage.fromFilePath(MainActivity.this, savedUri);
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), savedUri);
                    ivImagen.setImageBitmap(bitmap);
                    setAppState(AppState.IMAGE_DISPLAY);
                    analizarImagenEstatica(image);
                } catch (IOException e) {
                    Log.e(TAG, "Error al procesar la imagen guardada", e);
                }
            }
            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Error al tomar la foto: " + exception.getMessage(), exception);
            }
        });
    }

    private void openGallery() {
        // Detiene el análisis si está corriendo
        if (isAnalysisRunning) {
            toggleAnalysis();
        }
        pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    // --- Lógica de Análisis ---

    private void processImageProxy(ImageProxy imageProxy) { // Para análisis en vivo
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        @androidx.camera.core.ExperimentalGetImage
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            imageLabeler.process(image)
                    .addOnSuccessListener(labels -> runOnUiThread(() -> mostrarResultados(labels, true)))
                    .addOnFailureListener(e -> Log.e(TAG, "Fallo en el etiquetado en vivo", e))
                    .addOnCompleteListener(task -> {
                        isProcessing.set(false);
                        imageProxy.close();
                    });
        } else {
            isProcessing.set(false);
            imageProxy.close();
        }
    }

    private void analizarImagenEstatica(InputImage image) { // Para foto o galería
        tvResultados.setText("Analizando imagen...");
        tvDatoCurioso.setVisibility(View.GONE);
        imageLabeler.process(image)
                .addOnSuccessListener(labels -> runOnUiThread(() -> mostrarResultados(labels, false)))
                .addOnFailureListener(e -> {
                    tvResultados.setText("Error al analizar la imagen.");
                    Log.e(TAG, "Fallo en el etiquetado de imagen estática", e);
                });
    }


    // --- UI y Resultados ---

    private void setAppState(AppState state) {
        if (state == AppState.LIVE_CAMERA) {
            previewView.setVisibility(View.VISIBLE);
            ivImagen.setVisibility(View.GONE);
            btnCloseImage.setVisibility(View.GONE);
            tvResultados.setText("Elige una opción abajo");
            tvDatoCurioso.setVisibility(View.GONE);
        } else { // IMAGE_DISPLAY
            previewView.setVisibility(View.GONE);
            ivImagen.setVisibility(View.VISIBLE);
            btnCloseImage.setVisibility(View.VISIBLE);
        }
    }

    private void mostrarResultados(List<ImageLabel> labels, boolean esEnVivo) {
        if (esEnVivo && !isAnalysisRunning) return;

        tvDatoCurioso.setVisibility(View.GONE);
        if (labels.isEmpty()) {
            tvResultados.setText("¡Uy! No reconozco nada. Intenta de nuevo.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        String primerResultadoParaHablar = "";
        String datoCuriosoParaHablar = "";
        boolean esElPrimero = true;

        for (ImageLabel label : labels) {
            String textoEnIngles = label.getText();
            String traduccion = traducciones.getOrDefault(textoEnIngles.toLowerCase(), textoEnIngles);
            sb.append(traduccion).append(" (").append(Math.round(label.getConfidence() * 100)).append("%)\n");

            if (esElPrimero) {
                primerResultadoParaHablar = "¡Veo " + traduccion + "!";
                String datoCurioso = datosCuriosos.get(textoEnIngles);
                if (datoCurioso != null) {
                    tvDatoCurioso.setText(datoCurioso);
                    tvDatoCurioso.setVisibility(View.VISIBLE);
                    datoCuriosoParaHablar = datoCurioso;
                }
                esElPrimero = false;
            }
        }

        tvResultados.setText(sb.toString().trim());
        hablar(primerResultadoParaHablar + " " + datoCuriosoParaHablar);
    }


    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Locale spanish = new Locale("spa", "ESP");
            int result = tts.setLanguage(spanish);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "El idioma español no está soportado.");
            }
        } else {
            Log.e("TTS", "Falló la inicialización del TTS.");
        }
    }

    private void hablar(String texto) {
        if (tts != null && !tts.isSpeaking() && texto != null && !texto.trim().isEmpty()) {
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "" + System.currentTimeMillis());
        }
    }

    private void crearMapaDeTraducciones() {
        traducciones = new HashMap<>();
        // Animales
        traducciones.put("Animal", "Animal");
        traducciones.put("Dog", "Perro");
        traducciones.put("Cat", "Gato");
        traducciones.put("Bird", "Pájaro");
        traducciones.put("Fish", "Pez");
        traducciones.put("Horse", "Caballo");
        traducciones.put("Cow", "Vaca");
        traducciones.put("Sheep", "Oveja");
        traducciones.put("Pig", "Cerdo");
        traducciones.put("Chicken", "Pollo");
        traducciones.put("Duck", "Pato");
        traducciones.put("Lion", "León");
        traducciones.put("Tiger", "Tigre");
        traducciones.put("Bear", "Oso");
        traducciones.put("Elephant", "Elefante");
        traducciones.put("Monkey", "Mono");

        // Personas y Ropa
        traducciones.put("Person", "Persona");
        traducciones.put("Man", "Hombre");
        traducciones.put("Woman", "Mujer");
        traducciones.put("Child", "Niño/a");
        traducciones.put("Baby", "Bebé");
        traducciones.put("Clothing", "Ropa");
        traducciones.put("T-shirt", "Camiseta");
        traducciones.put("Pants", "Pantalones");
        traducciones.put("Jeans", "Vaqueros");
        traducciones.put("Dress", "Vestido");
        traducciones.put("Hat", "Sombrero");
        traducciones.put("Shoe", "Zapato");
        traducciones.put("Footwear", "Calzado");
        traducciones.put("Sneakers", "Zapatillas");
        traducciones.put("Glasses", "Gafas");

        // Comida y Bebida
        traducciones.put("Food", "Comida");
        traducciones.put("Fruit", "Fruta");
        traducciones.put("Apple", "Manzana");
        traducciones.put("Banana", "Plátano");
        traducciones.put("Orange", "Naranja");
        traducciones.put("Strawberry", "Fresa");
        traducciones.put("Vegetable", "Verdura");
        traducciones.put("Carrot", "Zanahoria");
        traducciones.put("Pizza", "Pizza");
        traducciones.put("Hamburger", "Hamburguesa");
        traducciones.put("Sandwich", "Sándwich");
        traducciones.put("Drink", "Bebida");
        traducciones.put("Water", "Agua");
        traducciones.put("Juice", "Zumo");

        // Objetos y Juguetes
        traducciones.put("Toy", "Juguete");
        traducciones.put("Doll", "Muñeca");
        traducciones.put("Action figure", "Figura de acción");
        traducciones.put("Ball", "Pelota");
        traducciones.put("Balloon", "Globo");
        traducciones.put("Book", "Libro");
        traducciones.put("Computer", "Computadora");
        traducciones.put("Laptop", "Portátil");
        traducciones.put("Mobile phone", "Teléfono");
        traducciones.put("Smartphone", "Teléfono");
        traducciones.put("Watch", "Reloj");
        traducciones.put("Chair", "Silla");
        traducciones.put("Table", "Mesa");
        traducciones.put("Bed", "Cama");
        traducciones.put("Sofa", "Sofá");
        traducciones.put("Television", "Televisión");

        // Naturaleza
        traducciones.put("Plant", "Planta");
        traducciones.put("Tree", "Árbol");
        traducciones.put("Flower", "Flor");
        traducciones.put("Sky", "Cielo");
        traducciones.put("Cloud", "Nube");
        traducciones.put("Sun", "Sol");
        traducciones.put("Moon", "Luna");
        traducciones.put("Mountain", "Montaña");
        traducciones.put("Sea", "Mar");
        traducciones.put("Beach", "Playa");

        // Vehículos
        traducciones.put("Vehicle", "Vehículo");
        traducciones.put("Car", "Coche");
        traducciones.put("Bus", "Autobús");
        traducciones.put("Truck", "Camión");
        traducciones.put("Bicycle", "Bicicleta");
        traducciones.put("Motorcycle", "Motocicleta");
        traducciones.put("Airplane", "Avión");
        traducciones.put("Train", "Tren");
        traducciones.put("Boat", "Barco");

        // Otros
        traducciones.put("Building", "Edificio");
        traducciones.put("House", "Casa");
        traducciones.put("Smile", "Sonrisa");
    }

    private void crearMapaDeDatosCuriosos() {
        datosCuriosos = new HashMap<>();
        datosCuriosos.put("Dog", "¿Sabías que los perros pueden oler cosas que nosotros ni imaginamos?");
        datosCuriosos.put("Cat", "¿Sabías que los gatos duermen casi todo el día para guardar energía?");
        datosCuriosos.put("Bird", "¿Sabías que los pájaros son familia de los dinosaurios?");
        datosCuriosos.put("Fish", "¿Sabías que los peces beben agua para no tener sed?");
        datosCuriosos.put("Banana", "¿Sabías que los plátanos son como una varita de energía para los deportistas?");
        datosCuriosos.put("Apple", "¿Sabías que hay miles de tipos de manzanas en el mundo?");
        datosCuriosos.put("Car", "¿Sabías que el primer coche no corría más rápido que una persona caminando?");
        datosCuriosos.put("Bicycle", "¿Sabías que andar en bici es un súper ejercicio para tus piernas?");
        datosCuriosos.put("Sun", "¿Sabías que el Sol es una estrella gigante que nos da luz y calor?");
        datosCuriosos.put("Moon", "¿Sabías que la Luna es como el gran farol de la noche?");
        datosCuriosos.put("Cloud", "¿Sabías que las nubes están hechas de gotitas de agua muy pequeñas?");
        datosCuriosos.put("Book", "¿Sabías que cada libro es una aventura nueva esperando a ser leída?");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        cameraExecutor.shutdown();
    }
}

