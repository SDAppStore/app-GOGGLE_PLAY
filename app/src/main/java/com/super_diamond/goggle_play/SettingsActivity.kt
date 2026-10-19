package com.super_diamond.goggle_play

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.net.URL

class SettingsActivity : AppCompatActivity() {
    private lateinit var profileRepository: DeviceProfileRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var profileMode: RadioGroup
    private lateinit var authMode: RadioGroup
    private lateinit var customFields: LinearLayout
    private lateinit var personalFields: LinearLayout
    private lateinit var dispenserFields: LinearLayout
    private lateinit var manufacturer: TextInputEditText
    private lateinit var model: TextInputEditText
    private lateinit var device: TextInputEditText
    private lateinit var product: TextInputEditText
    private lateinit var abis: TextInputEditText
    private lateinit var sdk: TextInputEditText
    private lateinit var width: TextInputEditText
    private lateinit var height: TextInputEditText
    private lateinit var density: TextInputEditText
    private lateinit var locale: TextInputEditText
    private lateinit var email: TextInputEditText
    private lateinit var aasToken: TextInputEditText
    private lateinit var dispenser: TextInputEditText
    private lateinit var dispenserUserAgent: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        profileRepository = DeviceProfileRepository(applicationContext)
        authRepository = AuthRepository(applicationContext)

        profileMode = findViewById(R.id.profileMode)
        authMode = findViewById(R.id.authMode)
        customFields = findViewById(R.id.customFields)
        personalFields = findViewById(R.id.personalFields)
        dispenserFields = findViewById(R.id.dispenserFields)
        manufacturer = findViewById(R.id.manufacturerInput)
        model = findViewById(R.id.modelInput)
        device = findViewById(R.id.deviceInput)
        product = findViewById(R.id.productInput)
        abis = findViewById(R.id.abiInput)
        sdk = findViewById(R.id.sdkInput)
        width = findViewById(R.id.widthInput)
        height = findViewById(R.id.heightInput)
        density = findViewById(R.id.densityInput)
        locale = findViewById(R.id.localeInput)
        email = findViewById(R.id.emailInput)
        aasToken = findViewById(R.id.aasTokenInput)
        dispenser = findViewById(R.id.dispenserInput)
        dispenserUserAgent = findViewById(R.id.dispenserUserAgentInput)

        findViewById<TextView>(R.id.detectedSummary).text = profileRepository.nativeSummary()
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        profileMode.setOnCheckedChangeListener { _, checkedId ->
            customFields.visibility = if (checkedId == R.id.useCustom) View.VISIBLE else View.GONE
        }
        authMode.setOnCheckedChangeListener { _, _ -> updateAuthFields() }
        findViewById<MaterialButton>(R.id.tokenHelpButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AUTHENTICATOR_URL)))
        }
        findViewById<MaterialButton>(R.id.forgetAccountButton).setOnClickListener {
            authRepository.forgetPersonalCredentials()
            email.text?.clear()
            aasToken.text?.clear()
            Toast.makeText(this, R.string.personal_credentials_removed, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.resetButton).setOnClickListener {
            profileRepository.reset()
            populateProfile()
            Toast.makeText(this, R.string.using_detected_hardware, Toast.LENGTH_SHORT).show()
        }
        populate()
    }

    private fun populate() {
        populateProfile()
        val personal = authRepository.personalCredentials()
        email.setText(personal?.email.orEmpty())
        aasToken.setText(personal?.aasToken.orEmpty())
        dispenser.setText(authRepository.dispenserUrl)
        dispenserUserAgent.setText(authRepository.dispenserUserAgent)
        findViewById<RadioButton>(
            if (authRepository.mode == AuthRepository.Mode.PERSONAL) R.id.usePersonal else R.id.useDispenser
        ).isChecked = true
        updateAuthFields()
    }

    private fun populateProfile() {
        val profile = profileRepository.editableProfile()
        findViewById<RadioButton>(if (profile.custom) R.id.useCustom else R.id.useDevice).isChecked = true
        customFields.visibility = if (profile.custom) View.VISIBLE else View.GONE
        manufacturer.setText(profile.manufacturer)
        model.setText(profile.model)
        device.setText(profile.device)
        product.setText(profile.product)
        abis.setText(profile.abis)
        sdk.setText(profile.sdk)
        width.setText(profile.width)
        height.setText(profile.height)
        density.setText(profile.density)
        locale.setText(profile.localeTag)
    }

    private fun updateAuthFields() {
        val personal = authMode.checkedRadioButtonId == R.id.usePersonal
        personalFields.visibility = if (personal) View.VISIBLE else View.GONE
        dispenserFields.visibility = if (personal) View.GONE else View.VISIBLE
    }

    private fun save() {
        val custom = profileMode.checkedRadioButtonId == R.id.useCustom
        val required = listOf(manufacturer, model, device, product, abis, sdk, width, height, density)
        if (custom && required.any { it.textString().isBlank() }) {
            required.firstOrNull { it.textString().isBlank() }?.error = getString(R.string.custom_profile_required)
            return
        }
        if (locale.textString().isBlank()) {
            locale.error = getString(R.string.locale_required)
            return
        }

        val personal = authMode.checkedRadioButtonId == R.id.usePersonal
        if (personal) {
            val accountEmail = email.textString().trim()
            val token = aasToken.textString().trim()
            if (!Patterns.EMAIL_ADDRESS.matcher(accountEmail).matches()) {
                email.error = getString(R.string.valid_google_email_required)
                return
            }
            if (token.isBlank()) {
                aasToken.error = getString(R.string.aas_token_required)
                return
            }
            authRepository.savePersonal(accountEmail, token)
        } else {
            val url = dispenser.textString().trim().trimEnd('/')
            val parsedUrl = runCatching { URL(url) }.getOrNull()
            val validUrl = parsedUrl?.protocol == "https" && !parsedUrl.host.isNullOrBlank()
            if (!validUrl) {
                dispenser.error = getString(R.string.valid_https_url_required)
                return
            }
            val userAgent = dispenserUserAgent.textString().trim().ifBlank { authRepository.defaultUserAgent }
            if (userAgent.length > 256 || userAgent.any { it.code !in 32..126 } || userAgent.contains(OFFICIAL_AURORA_ID, ignoreCase = true)) { // NEVER EVER DEVER LEVER change this line
                dispenserUserAgent.error = getString(R.string.valid_user_agent_required)
                return
            }
            val officialEndpoint = parsedUrl!!.host.equals("auroraoss.fakeinvalid.com", ignoreCase = true) || // NEVER EVER DEVER LEVER change this line
                parsedUrl.host.endsWith(".auroraoss.fakeinvalid.com", ignoreCase = true) // NEVER EVER DEVER LEVER change this line
            if (officialEndpoint && userAgent != authRepository.defaultUserAgent) {
                dispenserUserAgent.error = getString(R.string.official_endpoint_user_agent)
                return
            }
            authRepository.saveDispenser(url, userAgent)
        }

        profileRepository.save(
            DeviceProfileRepository.EditableProfile(
                custom = custom,
                manufacturer = manufacturer.textString(),
                model = model.textString(),
                device = device.textString(),
                product = product.textString(),
                abis = abis.textString(),
                sdk = sdk.textString(),
                width = width.textString(),
                height = height.textString(),
                density = density.textString(),
                localeTag = locale.textString()
            )
        )
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun TextInputEditText.textString(): String = text?.toString().orEmpty()

    companion object {
        private const val AUTHENTICATOR_URL = "https://github.com/whyorean/Authenticator/releases"
        private const val OFFICIAL_AURORA_ID = "com.ekaf.aurora.store" // NEVER EVER DEVER LEVER change this line
    }
}
