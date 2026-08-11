package com.startupmini.nyachat.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Onboarding: wajib masuk dengan akun Google, lalu buat/masukkan PIN untuk
 * membuka workspace keluarga. Setiap anggota diidentifikasi lewat akun Google-nya,
 * dan data keluarga disatukan di Firestore lewat PIN (familyId = PIN).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinConnectScreen(
    onPinConnected: (String, String, String) -> Unit // PIN, Role, Name
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // rememberSaveable (P2-11): rotasi layar TIDAK boleh me-reset onboarding —
    // kalau reset, user yang sedang mengetik PIN/namanya tiba-tiba balik ke awal
    // dan (lebih parah) bisa mengubah alur menjadi seperti "PIN berbeda" padahal
    // tidak. State akun Google & alur PIN dipertahankan lintas rotasi.
    var generatedPin by rememberSaveable { mutableStateOf<String?>(null) }
    var inputPin by rememberSaveable { mutableStateOf("") }
    var myName by rememberSaveable { mutableStateOf("") }
    // State alur PIN tanpa animasi (AnimatedContent sebelumnya sering macet & tombol
    // bertumpuk): 0 = pilih Buat/Gunakan PIN, 1 = gabung PIN, 2 = PIN tergenerate.
    var pinFlowState by rememberSaveable { mutableIntStateOf(0) }
    var isSigningIn by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var signedInEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var pinCopied by rememberSaveable { mutableStateOf(false) }
    // Rate limiting percobaan join (anti brute-force PIN): catat waktu tiap
    // percobaan; setelah batas terlampaui, tombol join dikunci sementara.
    // remember (bukan rememberSaveable): reset saat rotasi tidak berbahaya —
    // limiter ini pelengkap, penegak utama ada di persetujuan owner + rules.
    val pinAttempts = remember { mutableListOf<Long>() }
    var pinRateError by remember { mutableStateOf<String?>(null) }

    val defaultName = stringResource(R.string.pin_default_name)
    val defaultGoogleName = stringResource(R.string.pin_default_google_name)

    // Lint LocalContextGetResourceValueCall (compose-bom 2026.06): jangan query
    // resource via LocalContext di dalam fungsi non-composable/coroutine — resolve
    // di composable scope via stringResource; template berargumen pakai .format().
    val strGoogleNotConfigured = stringResource(R.string.google_not_configured)
    val strGoogleSignInFailed = stringResource(R.string.google_sign_in_failed)
    val strSha1Hint = stringResource(R.string.google_err_sha1_hint)
    val strCredentialProvider = stringResource(R.string.google_err_credential_provider)
    val strProviderNotEnabled = stringResource(R.string.google_err_provider_not_enabled)
    val strInvalidCredential = stringResource(R.string.google_err_invalid_credential)
    val strNetworkError = stringResource(R.string.google_err_network)
    val strUnknownError = stringResource(R.string.google_err_unknown)
    val strRateLimited = stringResource(R.string.pin_rate_limited)

    // Web client ID (default_web_client_id) — dihasilkan plugin google-services dari
    // oauth_client web di google-services.json. Dibaca lewat getIdentifier() supaya
    // app tetap KOMPIL walau google-services.json minimal (oauth_client kosong, mis.
    // saat project Firebase baru belum didaftarkan SHA-1). Saat resource ada, nilainya
    // dipertahankan di APK release via tools:keep (app/src/main/res/values/keep.xml).
    // Referensi STATIS via R.string: jeda resource shrinker bahwa resource ini
    // dipakai, sehingga default_web_client_id TIDAK dibuang dari APK release.
    // (Sebelumnya dibaca via context.getIdentifier() yang bukan referensi
    // kompil-dilihat shrinker → resource hilang di release → login Google
    // gagal dng "Google Sign-In belum dikonfigurasi" walau Firebase sudah
    // aktif & SHA-1 sudah didaftarkan. getString pakai resource yang TIDAK
    // mungkin kosong karena plugin google-services selalu men-generate-nya.)
    // runCatching: stringResource melempar jika resource absen (project Firebase
    // baru) — aman ditangkap, sama seperti getString lama di dalam remember.
    val webClientId = runCatching {
        stringResource(R.string.default_web_client_id)
    }.getOrNull()

    // Pulihkan sesi Google yang masih aktif (misal app ditutup tanpa logout).
    LaunchedEffect(Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            signedInEmail = user.email ?: user.displayName
            if (myName.isBlank()) myName = user.displayName ?: defaultGoogleName
        }
    }

    /** SHA-1 sidik jari sertifikat penandatangan APK yang terpasang — persis
     *  nilai yang harus didaftarkan di Firebase Console → Pengaturan project →
     *  Aplikasi Anda → Tambahkan sidik jari. */
    @Suppress("DEPRECATION") // GET_SIGNATURES butuh API < 28; tetap dipakai agar support Android 7+
    fun signingCertSha1(context: Context): String? = try {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNATURES
        )
        val cert = info.signatures?.firstOrNull() ?: return null
        val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
        sha1.joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        null
    }

    fun startGoogleSignIn() {
        val clientId = webClientId
        if (clientId == null) {
            authError = strGoogleNotConfigured
            return
        }
        scope.launch {
            isSigningIn = true
            authError = null
            try {
                val credentialManager = CredentialManager.create(context)
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(
                        GetGoogleIdOption.Builder()
                            .setServerClientId(clientId)
                            .setFilterByAuthorizedAccounts(false)
                            .build()
                    )
                    .build()
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val idToken = try {
                        GoogleIdTokenCredential.createFrom(credential.data).idToken
                    } catch (e: GoogleIdTokenParsingException) {
                        null
                    }
                    if (idToken != null) {
                        val auth = FirebaseAuth.getInstance()
                        auth.signInWithCredential(
                            GoogleAuthProvider.getCredential(idToken, null)
                        ).await()
                        val user = auth.currentUser
                        signedInEmail = user?.email ?: user?.displayName
                        user?.displayName?.let {
                            if (myName.isBlank()) myName = it
                        }
                    } else {
                        authError = strGoogleSignInFailed
                    }
                } else {
                    // Kredensial bukan Google ID Token (mis. passkey) — tidak dipakai untuk login Google.
                    authError = strGoogleSignInFailed
                }
            } catch (e: GetCredentialCancellationException) {
                // User membatalkan dialog Google — bukan error, biarkan tenang.
            } catch (e: GetCredentialException) {
                // Google Play Services menolak request (mis. SHA-1/package belum
                // terdaftar di Firebase Console, atau app tidak dipercaya). Kode
                // 10/15 = "Developer console is not set up correctly" — artinya
                // sidik jari penandatangan belum terdaftar, bukan provider mati.
                // Tampilkan SHA-1 asli APK ini supaya user tinggal salin & daftarkan
                // di Firebase Console tanpa perlu mencari-cari.
                val sha1 = signingCertSha1(context)
                val hint = if (sha1 != null) {
                    strSha1Hint.format(sha1)
                } else {
                    ""
                }
                authError = strCredentialProvider.format(e.type) + hint
            } catch (e: FirebaseAuthException) {
                // Tampilkan penyebab sebenarnya supaya user tahu harus apa.
                authError = when (e.errorCode) {
                    "auth/operation-not-allowed" ->
                        strProviderNotEnabled
                    "auth/invalid-credential", "auth/invalid-id-token",
                    "auth/user-disabled", "auth/user-not-found" ->
                        strInvalidCredential
                    "auth/network-request-failed" ->
                        strNetworkError
                    else ->
                        strUnknownError.format(e.message ?: e.errorCode)
                }
            } catch (e: Exception) {
                authError = strUnknownError.format(
                    e.message ?: e.javaClass.simpleName
                )
            } finally {
                isSigningIn = false
            }
        }
    }

    fun signOutGoogle() {
        scope.launch {
            FirebaseAuth.getInstance().signOut()
            signedInEmail = null
            myName = ""
            pinFlowState = 0
            generatedPin = null
            authError = null
        }
    }

    val email = signedInEmail

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_logo),
                    contentDescription = stringResource(R.string.pin_image_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(120.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.pin_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = stringResource(R.string.pin_subtitle),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 28.dp)
                )

                if (email == null) {
                    // ---- Gate: wajib masuk Google dulu ----
                    GoogleSignInCard(
                        isSigningIn = isSigningIn,
                        authError = authError,
                        onSignInClick = { startGoogleSignIn() }
                    )
                } else {
                    // ---- Sudah masuk Google: akun + PIN flow ----
                    AccountChip(email = email, onSignOut = { signOutGoogle() })

                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedTextField(
                        value = myName,
                        onValueChange = { myName = it },
                        label = { Text(stringResource(R.string.pin_name_label)) },
                        trailingIcon = {
                            if (myName.isNotBlank()) {
                                IconButton(onClick = { myName = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.pin_name_clear)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    when (pinFlowState) {
                        2 -> {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    ),
                                    elevation = CardDefaults.cardElevation(0.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(stringResource(R.string.pin_your_pin), style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            // Audit ketahanan: pinFlowState==2 hanya dicapai
                                            // SETELAH generatedPin di-set — fallback "" aman.
                                            text = generatedPin.orEmpty(),
                                            style = MaterialTheme.typography.displayMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            letterSpacing = 8.sp,
                                            modifier = Modifier.padding(vertical = 12.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.pin_share_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Text(
                                            text = stringResource(R.string.pin_create_warning),
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        // PIN bisa disalin ke clipboard — biar gampang dikirim ke pasangan.
                                        // L7: pakai ClipData dengan label agar app lain tidak bisa membaca
                                        // isi clipboard tanpa izin (clipboard overlay/privacy API ≥ 31) —
                                        // setText(AnnotatedString) lama tidak menyertakan label.
                                        OutlinedButton(
                                            onClick = {
                                                generatedPin?.let {
                                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Nyachat PIN", it))
                                                    pinCopied = true
                                                    scope.launch {
                                                        delay(2000)
                                                        pinCopied = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (pinCopied) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCopy,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(if (pinCopied) R.string.pin_copied else R.string.pin_copy),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(20.dp))

                                        Button(
                                            onClick = {
                                                // Audit ketahanan: PIN hanya tersambung bila
                                                // sudah dibangkitkan (state 2) — guard null
                                                // mencegah NPE pada jalur tak terduga.
                                                generatedPin?.let { pin ->
                                                    onPinConnected(pin, Constants.Roles.OWNER, myName.ifBlank { defaultName })
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().height(56.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            Text(stringResource(R.string.pin_enter), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            1 -> {
                                OutlinedTextField(
                                    value = inputPin,
                                    onValueChange = {
                                        if (it.length <= Constants.Defaults.PIN_LENGTH) inputPin = it.uppercase()
                                    },
                                    label = { Text(stringResource(R.string.pin_input_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        cursorColor = MaterialTheme.colorScheme.primary
                                    )
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        // Rate limiting dulu — kalau sedang lockout, tolak
                                        // percobaan dan tampilkan sisa waktu tunggu.
                                        val now = System.currentTimeMillis()
                                        if (PinAttemptLimiter.lockoutEndsAt(pinAttempts, now) != null) {
                                            pinRateError = strRateLimited.format(
                                                PinAttemptLimiter.remainingLockSeconds(pinAttempts, now)
                                            )
                                        } else if (inputPin.length >= Constants.Defaults.PIN_MIN_LEGACY_LENGTH) {
                                            // Terima PIN 6–8 digit: workspace lama boleh 6 digit,
                                            // workspace baru wajib 8 digit (Constants.Defaults.PIN_LENGTH).
                                            pinRateError = null
                                            pinAttempts.add(now)
                                            onPinConnected(inputPin, Constants.Roles.MEMBER, myName.ifBlank { defaultName })
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    enabled = inputPin.length >= Constants.Defaults.PIN_MIN_LEGACY_LENGTH,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text(stringResource(R.string.pin_join), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                pinRateError?.let {
                                    Text(
                                        text = it,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth()
                                    )
                                }

                                Text(
                                    text = stringResource(R.string.pin_join_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp)
                                )

                                TextButton(
                                    onClick = { pinFlowState = 0 },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            else -> {                                        Button(
                                            onClick = {
                                                // PIN baru 8 digit (ruang kunci 10^8, bukan 10^6) —
                                                // lihat Constants.Defaults.PIN_LENGTH. Dipakai
                                                // SecureRandom: PIN adalah password bersama
                                                // workspace, jadi tidak boleh berasal dari PRNG
                                                // biasa (java.util.Random) yang bisa ditebak.
                                                val len = Constants.Defaults.PIN_LENGTH
                                                val random = SecureRandom()
                                                generatedPin = buildString {
                                                    append((1 + random.nextInt(9)).toString()) // digit pertama 1–9
                                                    repeat(len - 1) { append(random.nextInt(10).toString()) }
                                                }
                                                pinFlowState = 2
                                            },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text(stringResource(R.string.pin_create), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedButton(
                                    onClick = { pinFlowState = 1 },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(stringResource(R.string.pin_use), fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                    }
                }
            }

            // Label versi kecil di bawah layar login — biar mudah memastikan APK
            // yang terpasang dan melaporkan versinya saat ada masalah.
            Text(
                text = stringResource(
                    R.string.login_version_label,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun GoogleSignInCard(
    isSigningIn: Boolean,
    authError: String?,
    onSignInClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onSignInClick,
            enabled = !isSigningIn,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_google_logo),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(if (isSigningIn) R.string.google_signing_in else R.string.google_sign_in),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        authError?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun AccountChip(
    email: String,
    onSignOut: () -> Unit
) {
    val initial = email.firstOrNull()?.uppercase() ?: "?"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar inisial — dekoratif, email sudah tertera di sampingnya (P3-2).
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.google_signed_in_as, email),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onSignOut) {
            Text(
                text = stringResource(R.string.google_sign_out),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
