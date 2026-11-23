package com.apktool.ultra.core

import android.util.Log
import com.apktool.ultra.ApktoolUltraApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import java.util.jar.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

class ApkSigner {

    data class SignResult(
        val success: Boolean,
        val signedApk: File?,
        val message: String
    )

    data class KeystoreInfo(
        val file: File,
        val alias: String,
        val storePassword: String,
        val keyPassword: String
    )

    suspend fun sign(
        apkFile: File,
        outputFile: File,
        keystoreInfo: KeystoreInfo? = null
    ): SignResult = withContext(Dispatchers.IO) {
        try {
            val ks: KeystoreInfo = keystoreInfo ?: run {
                val defaultKs = File(ApktoolUltraApp.keystoreDir, "apktool-ultra.jks")
                if (!defaultKs.exists()) {
                    generateKeystore(defaultKs, "apktool-ultra", "apktool123", "apktool123")
                }
                KeystoreInfo(defaultKs, "apktool-ultra", "apktool123", "apktool123")
            }

            val keyStore = KeyStore.getInstance("BKS")
            FileInputStream(ks.file).use { fis ->
                keyStore.load(fis, ks.storePassword.toCharArray())
            }

            val privateKey = keyStore.getKey(ks.alias, ks.keyPassword.toCharArray()) as PrivateKey
            val cert = keyStore.getCertificate(ks.alias) as X509Certificate

            val signedApk = signApkV1(apkFile, outputFile, privateKey, cert, ks.alias)
            SignResult(true, signedApk, "APK signed successfully")
        } catch (e: Exception) {
            Log.e("ApkSigner", "Sign failed", e)
            SignResult(false, null, "Sign failed: ${e.message}")
        }
    }

    fun generateKeystore(
        outputFile: File,
        alias: String,
        storePassword: String,
        keyPassword: String,
        cn: String = "ApktoolUltra",
        validity: Int = 10000
    ) {
        val keyStore = KeyStore.getInstance("BKS")
        keyStore.load(null, storePassword.toCharArray())

        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        val cert = generateSelfSignedCert(keyPair, cn, validity)
        keyStore.setKeyEntry(alias, keyPair.private, keyPassword.toCharArray(), arrayOf(cert))

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            keyStore.store(fos, storePassword.toCharArray())
        }
    }

    private fun generateSelfSignedCert(keyPair: KeyPair, cn: String, validityDays: Int): X509Certificate {
        val subject = X500Principal("CN=$cn, OU=ApktoolUltra, O=ApktoolUltra, L=Seoul, ST=Seoul, C=KR")
        val notBefore = Date()
        val notAfter = Date(notBefore.time + validityDays.toLong() * 24 * 60 * 60 * 1000)

        val serialNumber = java.math.BigInteger(64, SecureRandom())

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)

        val tbsCert = buildTbsCertificate(subject, serialNumber, notBefore, notAfter, keyPair.public)
        signer.update(tbsCert)
        val signature = signer.sign()

        val certBytes = buildCertificate(tbsCert, signature)
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }

    private fun buildTbsCertificate(
        subject: X500Principal,
        serial: java.math.BigInteger,
        notBefore: Date,
        notAfter: Date,
        publicKey: PublicKey
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.write(subject.encoded)
        return baos.toByteArray()
    }

    private fun buildCertificate(tbsCert: ByteArray, signature: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(tbsCert)
        baos.write(signature)
        return baos.toByteArray()
    }

    private fun signApkV1(
        inputApk: File,
        outputApk: File,
        privateKey: PrivateKey,
        cert: X509Certificate,
        alias: String
    ): File {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name("Created-By")] = "ApktoolUltra"

        val zipFile = ZipFile(inputApk)
        val entries = zipFile.entries().toList().filter { !it.name.startsWith("META-INF/") }

        for (entry in entries) {
            if (entry.isDirectory) continue
            val digest = MessageDigest.getInstance("SHA-256")
            zipFile.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val attrs = Attributes()
            attrs[Attributes.Name("SHA-256-Digest")] = Base64.getEncoder().encodeToString(digest.digest())
            manifest.entries[entry.name] = attrs
        }

        val manifestBytes = ByteArrayOutputStream().also {
            manifest.write(it)
        }.toByteArray()

        val sfContent = buildSignatureFile(manifestBytes, alias)
        val sigBlock = signData(sfContent, privateKey)

        outputApk.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputApk))).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(manifestBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/${alias.uppercase()}.SF"))
            zos.write(sfContent)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/${alias.uppercase()}.RSA"))
            zos.write(sigBlock)
            zos.closeEntry()

            for (entry in entries) {
                zos.putNextEntry(ZipEntry(entry.name))
                if (!entry.isDirectory) {
                    zipFile.getInputStream(entry).use { it.copyTo(zos) }
                }
                zos.closeEntry()
            }
        }
        zipFile.close()
        return outputApk
    }

    private fun buildSignatureFile(manifestBytes: ByteArray, alias: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val manifestDigest = Base64.getEncoder().encodeToString(digest.digest(manifestBytes))

        val sb = StringBuilder()
        sb.appendLine("Signature-Version: 1.0")
        sb.appendLine("Created-By: ApktoolUltra")
        sb.appendLine("SHA-256-Digest-Manifest: $manifestDigest")
        sb.appendLine()
        return sb.toString().toByteArray()
    }

    private fun signData(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }
}
