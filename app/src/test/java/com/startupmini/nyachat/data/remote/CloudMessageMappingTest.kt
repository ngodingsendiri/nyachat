package com.startupmini.nyachat.data.remote

import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.util.CustomClassMapper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresi BUG-1 (P0) — badge finansial hilang dari bubble chat.
 *
 * Akar masalah: property Kotlin Boolean `isFinancial` menghasilkan getter JVM
 * `isFinancial()` — CustomClassMapper Firestore menurunkan nama field jadi
 * "financial" (strip prefix "is"), sehingga `toObject(CloudMessage)` TIDAK
 * pernah membaca field cloud "isFinancial" (selalu default false) padahal cloud
 * menyimpan true. Snapshot listener lalu me-merge ulang dengan isFinancial=false
 * (detectedAmount tetap tersimpan), menghapus badge dari bubble.
 *
 * Fix: `@get:PropertyName("isFinancial")` pada property — memaksa nama field
 * eksplisit. Kedua test di bawah menjaga agar anotasi tidak hilang diam-diam:
 * 1) round-trip nyata lewat [CustomClassMapper] (jalur yang sama dengan toObject),
 * 2) keberadaan anotasi pada getter.
 */
class CloudMessageMappingTest {

    @Test
    fun `round-trip isFinancial via CustomClassMapper mempertahankan true`() {
        val original = CloudMessage(
            cloudId = "m1",
            isFinancial = true,
            detectedAmount = 25_000.0,
            detectedCategory = "Lain-lain"
        )
        // Jalur serialisasi & deserialisasi yang sama dengan Firestore.toObject().
        // null aman sebagai DocumentReference: CloudMessage tidak punya field bertipe itu.
        val plain = CustomClassMapper.convertToPlainJavaTypes(original) as Map<*, *>
        val back = CustomClassMapper.convertToCustomClass(plain, CloudMessage::class.java, null)

        assertEquals(true, back.isFinancial)
        assertEquals(25_000.0, back.detectedAmount ?: 0.0, 0.001)
        assertEquals("Lain-lain", back.detectedCategory)
    }

    @Test
    fun `getter isFinancial membawa anotasi PropertyName isFinancial`() {
        val getter = CloudMessage::class.java.getMethod("isFinancial")
        val annotation = getter.getAnnotation(PropertyName::class.java)
        assertEquals("isFinancial", annotation?.value)
    }
}
