"""Exercise production EC generation, keystore codecs, and both APK signing paths before assembleRelease."""
import importlib.util
import os
import sys
sys.dont_write_bytecode = True
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib
import zipfile
import urllib.request

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager/domain/manager"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
spec = importlib.util.spec_from_file_location("compat", Path(__file__).with_name("urv-import-compat-test.py"))
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)
artifact = compat.artifact
def cp(paths): return os.pathsep.join(map(str, paths))
def run(args): subprocess.run(list(map(str, args)), check=True, timeout=120)
kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]
compiler = [artifact("org.jetbrains.kotlin", n, kotlin) for n in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", n, kotlin) for n in ("kotlin-stdlib", "kotlin-test", "kotlin-test-junit")]
deps += [artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core"),
         artifact("org.bouncycastle", "bcprov-jdk15to18", "1.78.1"), ROOT / "libs/APKEditor-1.4.7.jar"]
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="p256-", dir=ROOT / "build") as directory:
    work = Path(directory)
    for name in ("bcpkix", "bcutil"):
        jar = work / (name + ".jar")
        urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/org/bouncycastle/" +
            name + "-jdk15to18/1.78.1/" + name + "-jdk15to18-1.78.1.jar", jar)
        deps.append(jar)
    library = work / "library.jar"
    aar = next((ROOT / ".gradle-user-home/caches/modules-2/files-2.1/app.revanced/revanced-library-android").rglob("*.aar"))
    with zipfile.ZipFile(aar) as archive: library.write_bytes(archive.read("classes.jar"))
    deps.append(library)
    deps.append(artifact("com.android.tools.build", "apksig"))
    manager = (BASE / "KeystoreManager.kt").read_text(encoding="utf-8")
    declarations = manager[manager.index("    private data class KeyMaterial("):manager.index("    private suspend fun updatePrefs(")]
    loading = manager[manager.index("    private fun keyStoreTypes("):manager.index("    private suspend fun signingKey(")]
    jks = manager[manager.index("    private fun tryLoadJksKeyMaterial("):manager.rfind("}")]
    codec = work / "KeystoreCodec.kt"
    codec.write_text("""package app.universal.patch.manager.domain.manager
import java.io.*
import java.security.*
import java.security.cert.*
import java.security.spec.*
import javax.crypto.EncryptedPrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
class KeystoreCodec {
    val bcProvider: Provider = BouncyCastleProvider()
""" + (declarations + loading + jks).replace("private ", "") + "}\n", encoding="utf-8")
    test = work / "KeystoreRoundTripTest.kt"
    test.write_text("""package app.universal.patch.manager.domain.manager
import org.junit.Test
import org.junit.Assert.*
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Date
class KeystoreRoundTripTest {
    @Test fun algorithmConversionIsExplicitAndPreservesTheInput() {
        val expiry = Date(253402300799000L)
        val original = generateP256SigningKey("original", expiry)
        val chain = listOf(original.second)
        val kept = convertedSigningKey(original.first, chain, "original", null, expiry)
        assertSame(original.first, kept.first)
        assertSame(chain, kept.second)
        val rsa = convertedSigningKey(original.first, chain, "EC, Unicode ?", SigningKeyAlgorithm.RSA, expiry)
        assertEquals("RSA", rsa.first.algorithm)
        assertNotEquals(original.second, rsa.second.single())
        val ec = convertedSigningKey(rsa.first, rsa.second, "converted", SigningKeyAlgorithm.ECDSA_P256, expiry)
        assertEquals(256, (ec.second.single().publicKey as ECPublicKey).params.curve.field.fieldSize)
        assertNotEquals(rsa.second.single(), ec.second.single())
        val codec = KeystoreCodec()
        for ((key, certs) in listOf(rsa, ec)) for (format in listOf("BKS", "JKS", "PKCS12")) {
            val bytes = codec.buildKeystoreBytes("converted", "key-password", "store-password", key, certs, format)
            val restored = codec.tryLoadKeyMaterial(bytes, "converted", "store-password", "key-password")!!
            assertEquals(certs, restored.keyMaterial.certificates)
            val signer = Signature.getInstance(if (key.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA")
            signer.initSign(restored.keyMaterial.privateKey)
            signer.update(byteArrayOf(4, 5, 6))
            val signature = signer.sign()
            signer.initVerify(certs.single())
            signer.update(byteArrayOf(4, 5, 6))
            assertTrue(signer.verify(signature))
        }
        assertEquals(original.second, chain.single())
    }

    @Test fun keysSurviveEveryFormatConversionAndJksFallback() {
        val codec = KeystoreCodec()
        val (key, cert) = generateP256SigningKey("EC, Unicode Ω", Date(253402300799000L))
        assertEquals(256, (cert.publicKey as ECPublicKey).params.curve.field.fieldSize)
        cert.verify(cert.publicKey)
        assertTrue(cert.serialNumber.toByteArray().size <= 20)
        for (source in listOf("BKS", "JKS", "PKCS12")) {
            val bytes = codec.buildKeystoreBytes("ec", "key-password", "store-password", key, listOf(cert), source)
            val imported = codec.tryLoadKeyMaterial(bytes, "ec", "store-password", "key-password")!!
            assertEquals(cert, imported.keyMaterial.certificates.single())
            if (source == "JKS") {
                assertEquals(cert, codec.tryLoadJksKeyMaterial(bytes, "ec", "store-password", "key-password")!!
                    .keyMaterial.certificates.single())
            }
            for (target in listOf("BKS", "JKS", "PKCS12")) {
                val converted = codec.buildKeystoreBytes("ec", "new-key-password", "new-store-password",
                    imported.keyMaterial.privateKey, imported.keyMaterial.certificates, target)
                val restored = codec.tryLoadKeyMaterial(converted, "ec", "new-store-password", "new-key-password")!!
                assertEquals(cert, restored.keyMaterial.certificates.single())
                val signer = Signature.getInstance("SHA256withECDSA")
                signer.initSign(restored.keyMaterial.privateKey)
                signer.update(byteArrayOf(1, 2, 3))
                val signature = signer.sign()
                signer.initVerify(cert)
                signer.update(byteArrayOf(1, 2, 3))
                assertTrue(signer.verify(signature))
            }
        }
    }
}
""", encoding="utf-8")
    sources = [BASE / "SigningKeyAlgorithm.kt", BASE / "ApkSigningCompatibility.kt", BASE / "InPlaceApkSigner.kt", codec, test,
        ROOT / "app/src/test/java/app/universal/patch/manager/domain/manager/InPlaceApkSignerTest.kt"]
    classes = work / "classes"
    run([JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
         "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps), "-d", classes, *sources])
    run([JAVA, "-cp", cp([classes] + deps), "org.junit.runner.JUnitCore",
         "app.universal.patch.manager.domain.manager.KeystoreRoundTripTest",
         "app.universal.patch.manager.domain.manager.InPlaceApkSignerTest"])
