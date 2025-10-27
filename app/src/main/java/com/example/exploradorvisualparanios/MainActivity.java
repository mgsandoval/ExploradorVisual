package com.example.exploradorvisualparanios;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
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

    private PreviewView previewView;
    private TextView tvResultados;
    private TextView tvDatoCurioso;
    private CardView cardResultados;
    private Button btnAnalizar;

    private ImageLabeler imageLabeler;
    private Map<String, String> traducciones;
    private Map<String, String> datosCuriosos;
    private TextToSpeech tts;

    private ExecutorService cameraExecutor;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private boolean isAnalysisRunning = false;
    private ProcessCameraProvider cameraProvider;

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "¡Necesitamos permiso de la cámara para funcionar!", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        tvResultados = findViewById(R.id.tvResultados);
        tvDatoCurioso = findViewById(R.id.tvDatoCurioso);
        cardResultados = findViewById(R.id.cardResultados);
        btnAnalizar = findViewById(R.id.btnAnalizar);

        cameraExecutor = Executors.newSingleThreadExecutor();

        tts = new TextToSpeech(this, this);
        ImageLabelerOptions options = new ImageLabelerOptions.Builder()
                .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                .build();
        imageLabeler = ImageLabeling.getClient(options);

        crearMapaDeTraducciones();
        crearMapaDeDatosCuriosos();

        btnAnalizar.setOnClickListener(v -> toggleAnalysis());

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
                Log.e("MainActivity", "Error al iniciar la cámara", e);
                Toast.makeText(this, "No se pudo iniciar la cámara", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) {
            return;
        }

        cameraProvider.unbindAll(); // Desvincula casos de uso anteriores

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        try {
            if (isAnalysisRunning) {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } else {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error al vincular casos de uso", e);
        }
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
                    .addOnSuccessListener(labels -> runOnUiThread(() -> mostrarResultados(labels)))
                    .addOnFailureListener(e -> Log.e("MainActivity", "Fallo en el etiquetado de imagen", e))
                    .addOnCompleteListener(task -> {
                        isProcessing.set(false);
                        imageProxy.close();
                    });
        } else {
            isProcessing.set(false);
            imageProxy.close();
        }
    }

    private void toggleAnalysis() {
        isAnalysisRunning = !isAnalysisRunning;
        if (isAnalysisRunning) {
            btnAnalizar.setText("Detener Análisis");
            tvResultados.setText("Analizando...");
            bindCameraUseCases(); // Re-vincula con el análisis de imagen
        } else {
            btnAnalizar.setText("Iniciar Análisis");
            tvResultados.setText("Análisis detenido. Apunta la cámara a un objeto.");
            tvDatoCurioso.setVisibility(View.GONE);
            isProcessing.set(false);
            bindCameraUseCases(); // Re-vincula sin el análisis de imagen
        }
    }

    private void mostrarResultados(List<ImageLabel> labels) {
        if (!isAnalysisRunning) return; // No mostrar si el análisis se detuvo

        tvDatoCurioso.setVisibility(View.GONE);

        if (labels.isEmpty()) {
            tvResultados.setText("¡Uy! No estoy seguro. Sigue apuntando...");
            return;
        }

        StringBuilder sb = new StringBuilder();
        String primerResultadoParaHablar = "";
        String datoCuriosoParaHablar = "";
        boolean esElPrimero = true;

        for (ImageLabel label : labels) {
            String textoEnIngles = label.getText();
            String traduccion = traducciones.getOrDefault(textoEnIngles, textoEnIngles);

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

    // --- MÉTODOS EXISTENTES (OnInit, hablar, crearMapas, onDestroy) ---
    // No necesitas cambiar los siguientes métodos. Cópialos tal cual.

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
        // ... (tu código de traducciones aquí, no cambia)
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
        traducciones.put("Vehicle", "Vehículo");
        traducciones.put("Car", "Coche");
        traducciones.put("Bus", "Autobús");
        traducciones.put("Truck", "Camión");
        traducciones.put("Bicycle", "Bicicleta");
        traducciones.put("Motorcycle", "Motocicleta");
        traducciones.put("Airplane", "Avión");
        traducciones.put("Train", "Tren");
        traducciones.put("Boat", "Barco");
        traducciones.put("Building", "Edificio");
        traducciones.put("House", "Casa");
        traducciones.put("Smile", "Sonrisa");
    }

    private void crearMapaDeDatosCuriosos() {
        datosCuriosos = new HashMap<>();
        // ... (tu código de datos curiosos aquí, no cambia)
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
