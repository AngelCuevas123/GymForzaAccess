package com.gymforza.access

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val Navy = Color(0xFF101827)
private val CardColor = Color(0xFF1C293B)
private val Green = Color(0xFF35D07F)
private val Red = Color(0xFFFF626B)
private val White = Color(0xFFFFFFFF)
private val Gray = Color(0xFF9BA8B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GymForzaAccessApp() }
    }
}

@Composable
fun GymForzaAccessApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checkingSession by remember { mutableStateOf(true) }
    var isAdmin by remember { mutableStateOf(false) }
    var adminName by remember { mutableStateOf("Administrador") }
    var message by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf<Boolean?>(null) }
    var member by remember { mutableStateOf<MemberInfo?>(null) }
    var scanResetKey by remember { mutableIntStateOf(0) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) message = "Se necesita permiso de cámara para escanear códigos QR."
    }

    // Recuperar una sesión existente y volver a comprobar el rol en el backend.
    LaunchedEffect(Unit) {
        try {
            val session = SupabaseProvider.client.auth.currentSessionOrNull()
            if (session != null) {
                val response = ApiProvider.api.getCurrentUser("Bearer ${session.accessToken}")
                val user = response.body()?.user
                if (response.isSuccessful && user?.role == "admin") {
                    adminName = user.fullName ?: user.email ?: "Administrador"
                    isAdmin = true
                } else {
                    SupabaseProvider.client.auth.signOut()
                }
            }
        } catch (_: Exception) {
            message = "No se pudo recuperar la sesión. Inicia sesión nuevamente."
            runCatching { SupabaseProvider.client.auth.signOut() }
        } finally {
            checkingSession = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
        when {
            checkingSession -> LoadingScreen()
            !isAdmin -> LoginScreen(
                email = email,
                onEmailChange = { email = it },
                password = password,
                onPasswordChange = { password = it },
                busy = busy,
                message = message,
                onLogin = {
                    if (email.isBlank() || password.isBlank()) {
                        message = "Escribe tu correo y contraseña."
                    } else {
                        scope.launch {
                            busy = true
                            message = ""
                            try {
                                SupabaseProvider.client.auth.signInWith(Email) {
                                    this.email = email.trim()
                                    this.password = password
                                }
                                val accessToken = SupabaseProvider.client.auth
                                    .currentSessionOrNull()?.accessToken
                                    ?: error("Supabase no devolvió una sesión.")

                                val response = ApiProvider.api.getCurrentUser("Bearer $accessToken")
                                val user = response.body()?.user

                                if (!response.isSuccessful) {
                                    SupabaseProvider.client.auth.signOut()
                                    error(
                                        if (response.code() == 403)
                                            "Tu usuario no tiene acceso al panel de administración."
                                        else
                                            "No se pudo verificar tu cuenta en Gym Forza."
                                    )
                                }

                                val adminUser = user?.takeIf { it.role == "admin" }
                                    ?: run {
                                        SupabaseProvider.client.auth.signOut()
                                        error("Esta aplicación es exclusiva para administradores.")
                                    }

                                adminName = adminUser.fullName ?: adminUser.email ?: "Administrador"
                                isAdmin = true
                                password = ""
                                message = ""
                            } catch (e: Exception) {
                                message = e.message
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "No se pudo iniciar sesión. Revisa tus datos."
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            )
            else -> AccessScreen(
                adminName = adminName,
                hasCameraPermission = hasCameraPermission,
                onRequestCamera = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                message = message,
                authorized = authorized,
                member = member,
                scanResetKey = scanResetKey,
                onQrDetected = { qrToken ->
                    scope.launch {
                        busy = true
                        message = "Validando código QR..."
                        authorized = null
                        member = null
                        try {
                            if (!qrToken.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                                authorized = false
                                message = "El contenido del QR no tiene un formato válido."
                            } else {
                                val session = SupabaseProvider.client.auth.currentSessionOrNull()
                                    ?: error("Tu sesión expiró. Inicia sesión nuevamente.")
                                val response = ApiProvider.api.validateQr(
                                    authorization = "Bearer ${session.accessToken}",
                                    request = QrRequest(qrToken.lowercase())
                                )

                                val result = response.body()
                                if (response.isSuccessful && result?.authorized == true) {
                                    authorized = true
                                    member = result.member
                                    message = result.message ?: "Acceso autorizado."
                                } else {
                                    authorized = false
                                    message = result?.message ?: response.backendMessage(
                                        when (response.code()) {
                                            401 -> "Sesión expirada. Inicia sesión nuevamente."
                                            403 -> "Acceso denegado."
                                            404 -> "Código QR no encontrado."
                                            409 -> "Este código QR ya fue utilizado."
                                            410 -> "El código QR ha expirado."
                                            else -> "No se pudo validar el código (HTTP ${response.code()})."
                                        }
                                    )
                                    if (response.code() == 401) {
                                        SupabaseProvider.client.auth.signOut()
                                        isAdmin = false
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            authorized = false
                            message = e.message
                                ?.takeIf { it.isNotBlank() }
                                ?: "No se pudo conectar con el servidor."
                        } finally {
                            busy = false
                            scanResetKey++
                        }
                    }
                },
                onLogout = {
                    scope.launch {
                        runCatching { SupabaseProvider.client.auth.signOut() }
                        isAdmin = false
                        authorized = null
                        member = null
                        message = ""
                    }
                },
                busy = busy
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Navy),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Green)
    }
}

@Composable
private fun LoginScreen(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    busy: Boolean,
    message: String,
    onLogin: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text("GYM FORZA", color = White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "CONTROL DE ACCESO",
            color = Green,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(42.dp))
        Text("Bienvenido", color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Inicia sesión con tu cuenta de administrador.", color = Gray, fontSize = 14.sp)
        Spacer(Modifier.height(26.dp))

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
            label = { Text("Correo electrónico") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = RoundedCornerShape(14.dp),
            colors = loginFieldColors()
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
            label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(14.dp),
            colors = loginFieldColors()
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(message, color = Red, fontSize = 13.sp)
        }
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onLogin,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green,
                contentColor = Navy
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (busy) CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Navy,
                strokeWidth = 2.dp
            ) else Text("INICIAR SESIÓN", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "Acceso exclusivo para personal autorizado.",
            color = Gray,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = White,
    unfocusedTextColor = White,
    focusedBorderColor = Green,
    unfocusedBorderColor = Gray,
    focusedLabelColor = Green,
    unfocusedLabelColor = Gray,
    cursorColor = Green
)

@Composable
private fun AccessScreen(
    adminName: String,
    hasCameraPermission: Boolean,
    onRequestCamera: () -> Unit,
    message: String,
    authorized: Boolean?,
    member: MemberInfo?,
    scanResetKey: Int,
    onQrDetected: (String) -> Unit,
    onLogout: () -> Unit,
    busy: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Navy).padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("GYM FORZA", color = White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                Text("CONTROL DE ACCESO", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            TextButton(onClick = onLogout) {
                Text("Salir", color = Gray)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Hola, $adminName", color = Gray, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        Text("Escanear membresía", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("Coloca el código QR del socio dentro del marco.", color = Gray, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(CardColor, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                CameraScanner(
                    modifier = Modifier.fillMaxSize(),
                    resetSignal = scanResetKey,
                    enabled = !busy,
                    onQrDetected = onQrDetected
                )
                Box(
                    modifier = Modifier.size(230.dp).border(
                        width = 3.dp,
                        color = Green,
                        shape = RoundedCornerShape(20.dp)
                    )
                )
                Text(
                    if (busy) "VALIDANDO..." else "CENTRA EL CÓDIGO QR",
                    color = White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Necesitamos acceso a la cámara", color = White)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestCamera) { Text("Permitir cámara") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                val resultLabel = when (authorized) {
                    true -> "ACCESO AUTORIZADO"
                    false -> "ACCESO DENEGADO"
                    null -> if (busy) "VALIDANDO ACCESO" else "ESPERANDO ESCANEO"
                }
                Text(
                    resultLabel,
                    color = when (authorized) {
                        true -> Green
                        false -> Red
                        null -> Gray
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    message.ifBlank { "Aún no se ha detectado ningún código." },
                    color = White,
                    fontSize = 14.sp
                )
                if (authorized == true && member != null) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Gray.copy(alpha = 0.25f))
                    Spacer(Modifier.height(12.dp))
                    Text(member.fullName ?: "Socio Gym Forza", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Plan: ${member.planName ?: "No disponible"}", color = Gray, fontSize = 14.sp)
                    Text("Vigencia hasta: ${member.endDate ?: "No disponible"}", color = Gray, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "GYM FORZA • SISTEMA DE ACCESO",
            color = Gray,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
@OptIn(ExperimentalGetImage::class)
private fun CameraScanner(
    modifier: Modifier = Modifier,
    resetSignal: Int,
    enabled: Boolean,
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val alreadyDetected = remember { AtomicBoolean(false) }
    val scanner: BarcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(resetSignal) {
        alreadyDetected.set(false)
    }

    LaunchedEffect(enabled) {
        if (!enabled) alreadyDetected.set(true)
    }

    DisposableEffect(lifecycleOwner) {
        val disposed = AtomicBoolean(false)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            if (disposed.get()) return@addListener

            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                    } else {
                        val input = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                val value = barcodes.firstOrNull()?.rawValue
                                if (enabled && value != null &&
                                    alreadyDetected.compareAndSet(false, true)
                                ) {
                                    ContextCompat.getMainExecutor(context).execute {
                                        onQrDetected(value)
                                    }
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                }

                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed.set(true)
            runCatching { cameraProvider?.unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView }
    )
}
