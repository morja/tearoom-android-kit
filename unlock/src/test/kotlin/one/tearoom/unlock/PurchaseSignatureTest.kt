package one.tearoom.unlock

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class PurchaseSignatureTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val json = """{"orderId":"GPA.1","productId":"pro","purchaseState":0}"""
    private val signature = Signature.getInstance("SHA1withRSA").run {
        initSign(keys.private)
        update(json.toByteArray())
        Base64.getEncoder().encodeToString(sign())
    }

    @Test fun `a signature from the Play key verifies`() {
        assertTrue(PurchaseSignature.verify(publicKey, json, signature))
    }

    @Test fun `changed data or another key fails`() {
        assertFalse(PurchaseSignature.verify(publicKey, json.replace("pro", "pra"), signature))
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        assertFalse(PurchaseSignature.verify(Base64.getEncoder().encodeToString(other.public.encoded), json, signature))
    }

    @Test fun `missing or malformed input fails closed`() {
        assertFalse(PurchaseSignature.verify(publicKey, json, null))
        assertFalse(PurchaseSignature.verify(publicKey, json, ""))
        assertFalse(PurchaseSignature.verify("not a key", json, signature))
        assertFalse(PurchaseSignature.verify(publicKey, json, "%%%"))
    }
}
