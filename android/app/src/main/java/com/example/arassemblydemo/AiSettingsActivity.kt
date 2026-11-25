package com.example.arassemblydemo

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity

/** One-time phone-local setup. The key is never prefilled, spoken, logged or included in the APK. */
class AiSettingsActivity:ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        LightSystemBars.configure(window)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val store=AiProviderSettings(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(16),dp(24),dp(24));setBackgroundColor(Color.WHITE) }
        LightSystemBars.insetContent(root,dp(24),dp(16),dp(24),dp(24))
        fun label(value:String,size:Float)=TextView(this).apply { text=value;textSize=size;setTextColor(Color.rgb(32,55,46));setPadding(0,dp(12),0,dp(12)) }
        root.addView(Button(this).apply{text=getString(R.string.settings_back);setOnClickListener{finish()}},LinearLayout.LayoutParams(-2,dp(48)))
        root.addView(label(getString(R.string.settings_title),28f))
        root.addView(label(getString(R.string.settings_direct),16f))
        val status=label(if(store.isConfigured())getString(R.string.settings_configured) else getString(R.string.settings_key_once),15f);root.addView(status)
        val input=EditText(this).apply { hint=if(store.isConfigured())getString(R.string.settings_replace) else "DeepSeek API Key";isSingleLine=true
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;isSaveEnabled=false }
        root.addView(input,LinearLayout.LayoutParams(-1,dp(60)))
        root.addView(label(getString(R.string.settings_privacy),14f))
        root.addView(Space(this),LinearLayout.LayoutParams(1,0,1f))
        root.addView(Button(this).apply { text=getString(R.string.settings_save);setOnClickListener {
            runCatching{store.save(input.text.toString().trim())}.onSuccess {
                input.text.clear();Toast.makeText(this@AiSettingsActivity,getString(R.string.settings_saved),Toast.LENGTH_SHORT).show();finish()
            }.onFailure { status.text=getString(R.string.settings_invalid) }
        }},LinearLayout.LayoutParams(-1,dp(56)))
        setContentView(root)
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
