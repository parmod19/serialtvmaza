package com.serialtvmaza

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast

object YtUi {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun showAccounts(ctx: Context) {
        val names = YtStore.names()
        val active = YtStore.active()
        val items = mutableListOf<String>()
        names.forEach { items += if (it == active) "✔ $it" else it }
        items += "➕ Login with Google"
        items += "📋 Paste cookies manually"
        items += "⚙ Playback settings"
        AlertDialog.Builder(ctx)
            .setTitle("YouTube Subs accounts")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < names.size -> accountMenu(ctx, names[which])
                    which == names.size -> showLogin(ctx)
                    which == names.size + 1 -> showPaste(ctx)
                    else -> YtSettings.showSettings(ctx)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun accountMenu(ctx: Context, name: String) {
        AlertDialog.Builder(ctx)
            .setTitle(name)
            .setItems(arrayOf("Use this account", "Delete")) { _, w ->
                if (w == 0) {
                    YtStore.setActive(name)
                    Toast.makeText(ctx, "Active: $name", Toast.LENGTH_SHORT).show()
                } else {
                    YtStore.remove(name)
                    Toast.makeText(ctx, "Deleted $name", Toast.LENGTH_SHORT).show()
                }
                showAccounts(ctx)
            }
            .setNegativeButton("Back") { _, _ -> showAccounts(ctx) }
            .show()
    }

    // Clears only Google/YouTube cookies so other plugins' WebView cookies stay intact
    private fun clearGoogleCookies(cm: CookieManager) {
        val hosts = listOf("google.com", "youtube.com")
        val urls = listOf(
            "https://accounts.google.com", "https://www.google.com", "https://google.com",
            "https://www.youtube.com", "https://youtube.com"
        )
        for (u in urls) {
            val names = cm.getCookie(u)?.split(";")?.map { it.substringBefore("=").trim() }.orEmpty()
            for (n in names) {
                if (n.isEmpty()) continue
                cm.setCookie(u, "$n=; Max-Age=0; Path=/")
                for (h in hosts) cm.setCookie(u, "$n=; Max-Age=0; Path=/; Domain=.$h")
            }
        }
        cm.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showLogin(ctx: Context) {
        val cm = CookieManager.getInstance()
        clearGoogleCookies(cm)
        cm.setAcceptCookie(true)

        val web = WebView(ctx)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = UA
        cm.setAcceptThirdPartyCookies(web, true)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Login to Google")
            .setView(web)
            .setNegativeButton("Cancel", null)
            .create()

        var done = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val host = Uri.parse(url ?: "").host ?: return
                if (done || !host.endsWith("youtube.com")) return
                val c = cm.getCookie("https://www.youtube.com") ?: return
                if (YtAuth.cookieValue(c, "SAPISID") != null) {
                    done = true
                    cm.flush()
                    val name = YtStore.add(c)
                    dialog.dismiss()
                    Toast.makeText(ctx, "Saved: $name (active)", Toast.LENGTH_LONG).show()
                    clearGoogleCookies(cm)
                }
            }
        }
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F")
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
    }

    private fun showPaste(ctx: Context) {
        val et = EditText(ctx)
        et.hint = "Paste full youtube.com cookie string (must contain SAPISID)"
        et.setLines(6)
        AlertDialog.Builder(ctx)
            .setTitle("Paste cookies")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val c = et.text.toString().trim()
                if (YtAuth.cookieValue(c, "SAPISID") == null) {
                    Toast.makeText(ctx, "SAPISID not found in cookies", Toast.LENGTH_LONG).show()
                } else {
                    val name = YtStore.add(c)
                    Toast.makeText(ctx, "Saved: $name (active)", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}