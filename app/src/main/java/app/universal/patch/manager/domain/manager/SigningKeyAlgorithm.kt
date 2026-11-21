package app.universal.patch.manager.domain.manager

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

enum class SigningKeyAlgorithm(val label: String) {
    RSA("RSA"),
    ECDSA_P256("ECDSA P-256")
}

/** Named curve parameters keep the key portable across Android and desktop keystores. */
internal fun generateP256SigningKey(alias: String, notAfter: Date): Pair<PrivateKey, X509Certificate> =
    generateSigningKey(alias, notAfter, SigningKeyAlgorithm.ECDSA_P256)

internal fun generateSigningKey(
    alias: String, notAfter: Date, algorithm: SigningKeyAlgorithm
): Pair<PrivateKey, X509Certificate> {
    val provider = BouncyCastleProvider()
    val random = SecureRandom()
    val ec = algorithm == SigningKeyAlgorithm.ECDSA_P256
    val generator = if (ec) KeyPairGenerator.getInstance("EC", provider) else KeyPairGenerator.getInstance("RSA")
    val pair = generator.apply {
        if (ec) initialize(ECGenParameterSpec("secp256r1"), random)
        else initialize(4096, random) // Preserve the existing RSA creation policy.
    }.generateKeyPair()
    val name = X500Principal(X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, alias).build().encoded)
    val signer = JcaContentSignerBuilder(if (ec) "SHA256withECDSA" else "SHA256withRSA")
        .apply { if (ec) setProvider(provider) }.build(pair.private)
    val holder = JcaX509v3CertificateBuilder(
        name, BigInteger(159, random).setBit(158),
        Date(System.currentTimeMillis() - 60_000), notAfter, name, pair.public
    ).build(signer)
    val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
    certificate.verify(pair.public, provider)
    return pair.private to certificate
}

/** Selecting an algorithm explicitly creates a new signing identity. */
internal fun convertedSigningKey(
    privateKey: PrivateKey,
    certificates: List<X509Certificate>,
    alias: String,
    algorithm: SigningKeyAlgorithm?,
    notAfter: Date
): Pair<PrivateKey, List<X509Certificate>> {
    if (algorithm == null) return privateKey to certificates
    val (key, certificate) = generateSigningKey(alias, notAfter, algorithm)
    return key to listOf(certificate)
}
