package one.tearoom.unlock

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Local check that Google Play signed a purchase (ADR-0002). Play signs `originalJson` with the app's
 * licence key using SHA1withRSA. This raises the bar for a patched Play client, not for a patched app.
 */
object PurchaseSignature {
    /** False for a missing or malformed signature or key, so a wrong key fails closed. */
    fun verify(publicKeyBase64: String, signedData: String, signature: String?): Boolean {
        if (signature.isNullOrBlank() || signedData.isBlank()) return false
        return try {
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))
            Signature.getInstance("SHA1withRSA").run {
                initVerify(key)
                update(signedData.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(signature.trim()))
            }
        } catch (_: Exception) {
            false
        }
    }
}
