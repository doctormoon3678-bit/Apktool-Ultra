package com.apktool.ultra.ui.signer

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apktool.ultra.ApktoolUltraApp
import com.apktool.ultra.core.ApkSigner
import com.apktool.ultra.databinding.ActivitySignerBinding
import com.apktool.ultra.utils.FileUtils
import kotlinx.coroutines.launch
import java.io.File

class SignerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignerBinding
    private val signer = ApkSigner()
    private var apkFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "APK Signer"
        binding.toolbar.setNavigationOnClickListener { finish() }

        val apkPath = intent.getStringExtra("apk_path") ?: run { finish(); return }
        apkFile = File(apkPath)

        binding.apkName.text = apkFile!!.name
        binding.apkSize.text = FileUtils.getFileSize(apkFile!!)

        binding.btnSign.setOnClickListener { signApk() }
        binding.btnGenerateKeystore.setOnClickListener { generateKeystore() }

        checkDefaultKeystore()
    }

    private fun checkDefaultKeystore() {
        val defaultKs = File(ApktoolUltraApp.keystoreDir, "apktool-ultra.jks")
        if (defaultKs.exists()) {
            binding.keystoreStatus.text = "Default keystore: Ready"
            binding.keystoreStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            binding.keystoreStatus.text = "No keystore found - will generate default"
            binding.keystoreStatus.setTextColor(0xFFFF9800.toInt())
        }
    }

    private fun signApk() {
        val apk = apkFile ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Signing..."
        binding.btnSign.isEnabled = false

        val useCustom = binding.switchCustomKeystore.isChecked

        lifecycleScope.launch {
            val outputFile = File(
                ApktoolUltraApp.workDir,
                apk.nameWithoutExtension + "_signed.apk"
            )

            val keystoreInfo = if (useCustom) {
                val ksPath = binding.editKeystorePath.text.toString()
                val alias = binding.editAlias.text.toString()
                val storePass = binding.editStorePassword.text.toString()
                val keyPass = binding.editKeyPassword.text.toString()

                if (ksPath.isEmpty() || alias.isEmpty() || storePass.isEmpty()) {
                    binding.statusText.text = "Please fill all keystore fields"
                    binding.progressBar.visibility = View.GONE
                    binding.btnSign.isEnabled = true
                    return@launch
                }

                ApkSigner.KeystoreInfo(File(ksPath), alias, storePass, keyPass.ifEmpty { storePass })
            } else null

            val result = signer.sign(apk, outputFile, keystoreInfo)

            binding.progressBar.visibility = View.GONE
            binding.btnSign.isEnabled = true
            binding.statusText.text = result.message

            if (result.success) {
                binding.outputPath.text = "Output: ${outputFile.absolutePath}"
                binding.outputPath.visibility = View.VISIBLE
                Toast.makeText(this@SignerActivity, "Signed successfully!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun generateKeystore() {
        val cn = binding.editCn.text.toString().ifEmpty { "ApktoolUltra" }
        val alias = binding.editNewAlias.text.toString().ifEmpty { "apktool-ultra" }
        val password = binding.editNewPassword.text.toString().ifEmpty { "apktool123" }

        try {
            val ksFile = File(ApktoolUltraApp.keystoreDir, "$alias.jks")
            signer.generateKeystore(ksFile, alias, password, password, cn)
            Toast.makeText(this, "Keystore generated: ${ksFile.name}", Toast.LENGTH_LONG).show()
            checkDefaultKeystore()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
