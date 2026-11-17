package app.runelift

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/* Health Connect ouvre cette page quand on demande pourquoi Runelift veut ces accès. */
class HealthPrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = WebView(this)
        w.webViewClient = WebViewClient()
        setContentView(w)
        w.loadUrl("https://gavaliagb-source.github.io/carnet-muscu/privacy.html#sante")
    }
}
