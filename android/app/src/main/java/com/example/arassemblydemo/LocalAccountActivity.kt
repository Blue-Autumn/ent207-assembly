package com.example.arassemblydemo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** PBKDF2 and synchronous durable writes are performed off the UI thread. */
class LocalAccountActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))

    private val main = Handler(Looper.getMainLooper())
    private lateinit var accounts: LocalAccounts
    private lateinit var name: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var login: Button
    private lateinit var register: Button
    private lateinit var logout: Button
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var form: LinearLayout
    private var createMode = false
    private var busy = false
    private var visible = false
    private val ink = Color.rgb(39,43,33)
    private val paper = Color.rgb(250,249,243)
    private val refresh = object : Runnable {
        override fun run() {
            if (!visible) return
            refreshIdentity()
            if (pendingOperations.get() > 0) main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightSystemBars.configure(window)
        accounts = LocalAccounts(this)
        createMode = savedInstanceState?.getBoolean("createMode") ?: false
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(22),dp(24),dp(22),dp(24))
        }
        val hero = FrameLayout(this).apply {
            clipToOutline = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffdfe8d3.toInt(),0xffedf0db.toInt())).apply { cornerRadius = dp(26).toFloat() }
        }
        hero.addView(PortfolioIconView(this,"projects",0xffc4d1b7.toInt()).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(197),dp(197),Gravity.BOTTOM or Gravity.END).apply { rightMargin=-dp(54); bottomMargin=-dp(56) })
        val copy = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(24),dp(60),dp(24))
        }
        title=label("",29f).apply { setTypeface(typeface,Typeface.BOLD) }
        subtitle=label("",15f)
        copy.addView(title); copy.addView(subtitle,row(7))
        hero.addView(copy)
        content.addView(hero,row(0))
        form = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(form,row(20))
        name=field(R.string.acct_username,InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            imeOptions=EditorInfo.IME_ACTION_NEXT
            filters=arrayOf(InputFilter.LengthFilter(24))
        }
        password=field(R.string.acct_password,InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            imeOptions=EditorInfo.IME_ACTION_DONE
            isSaveEnabled=false
            setOnEditorActionListener { _,action,_ ->
                if (action==EditorInfo.IME_ACTION_DONE) { submit(createMode); true } else false
            }
        }
        form.addView(name,row(0)); form.addView(password,row(8))
        status=label("",14f).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status,row(4))
        login=action(true) { submit(createMode) }
        register=action(false) {
            createMode=!createMode
            password.text.clear()
            status.text=""
            refreshIdentity()
        }
        logout=action(true) { submitLogout() }.apply { setText(R.string.acct_logout); visibility=View.GONE }
        content.addView(login,row(12)); content.addView(register,row(0)); content.addView(logout,row(12))
        refreshIdentity()
        val container=ScrollView(this).apply { isFillViewport=true; setBackgroundColor(paper); addView(content) }
        LightSystemBars.insetContent(container)
        setContentView(container)
        container.requestApplyInsets()
    }

    private fun field(hintId:Int,type:Int)=EditText(this).apply {
        setHint(hintId); isSingleLine=true; inputType=type
        setTextColor(ink); setHintTextColor(0xff687162.toInt()); textSize=16f
        background=surface(0xffeeefe7.toInt(),16)
        setPadding(dp(16),dp(14),dp(16),dp(14))
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private fun action(filled:Boolean, click:()->Unit)=Button(this).apply {
        isAllCaps=false; textSize=16f; minHeight=dp(50); stateListAnimator=null
        backgroundTintList=null; background=surface(if(filled) ink else Color.TRANSPARENT,25)
        setTextColor(if(filled) Color.WHITE else ink)
        setOnClickListener { click() }
    }
    private fun surface(color:Int,radius:Int)=GradientDrawable().apply {
        setColor(color); cornerRadius=dp(radius).toFloat()
    }
    private fun row(top:Int)=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(top) }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("createMode",createMode)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); visible=true; main.post(refresh) }
    override fun onStop() {
        visible=false; main.removeCallbacks(refresh); password.text.clear(); super.onStop()
    }

    private fun refreshIdentity() {
        val pending=pendingOperations.get()>0
        listOf(login,register,logout,name,password).forEach { it.isEnabled=!pending }
        if (pending) return
        val current=accounts.current()
        val signedIn=current!=null
        form.visibility=if(signedIn) View.GONE else View.VISIBLE
        login.visibility=form.visibility; register.visibility=form.visibility
        logout.visibility=if(signedIn) View.VISIBLE else View.GONE
        title.text=current?.name ?: getString(if(createMode) R.string.auth_create else R.string.acct_login)
        subtitle.setText(when {
            signedIn -> R.string.auth_manage_sub
            createMode -> R.string.auth_register_sub
            else -> R.string.auth_signin_sub
        })
        login.setText(if(createMode) R.string.auth_create else R.string.acct_login)
        register.setText(if(createMode) R.string.auth_existing else R.string.auth_new)
        accounts.lastError?.let { status.text=it }
    }

    private fun submit(create: Boolean) {
        if (busy || pendingOperations.get() > 0) return
        val username = name.text.toString()
        val secret = password.text.toString()
        busy = true
        pendingOperations.incrementAndGet()
        refreshIdentity()
        status.text = if (create) getString(R.string.acct_creating)
            else getString(R.string.acct_verifying)
        worker.execute {
            val result = if (create) accounts.register(username, secret) else accounts.login(username, secret)
            pendingOperations.decrementAndGet()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                login.isEnabled = true; register.isEnabled = true
                name.isEnabled = true; password.isEnabled = true
                password.text.clear()
                refreshIdentity()
                if (result.success) {
                    status.text = getString(R.string.acct_signed_in, result.account!!.name)
                    if (visible) {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                } else status.text = result.error ?: getString(R.string.acct_failed)
            }
        }
    }

    private fun submitLogout() {
        if (busy || pendingOperations.get() > 0) return
        busy = true
        pendingOperations.incrementAndGet()
        refreshIdentity()
        status.text = getString(R.string.acct_signing_out)
        worker.execute {
            val success = accounts.logout()
            val error = accounts.lastError
            pendingOperations.decrementAndGet()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                password.text.clear()
                refreshIdentity()
                status.text = if (success) getString(R.string.acct_signed_out) else error ?: getString(R.string.acct_logout_failed)
                if (success && visible) {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
            }
        }
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(39, 43, 33)); setLineSpacing(dp(2).toFloat(),1f)
    }
    companion object {
        // Submitted operations finish in order across rotation; they are not falsely called cancelled.
        private val worker = Executors.newSingleThreadExecutor()
        private val pendingOperations = AtomicInteger(0)
    }
}
