package com.futmondo.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.futmondo.core.FutmondoApi
import com.futmondo.core.FutmondoException
import com.futmondo.core.FutmondoService
import com.futmondo.core.MarketResult
import com.futmondo.core.Reports
import com.futmondo.core.SellCandidate

/**
 * Dos botones: Analizar y Revisar mercado.
 * La app solo PONE A LA VENTA, y únicamente lo que el usuario marca y confirma. No pujó nunca,
 * no hace venta directa, no responde ofertas y no cambia la alineación.
 */
class MainActivity : Activity() {
    private lateinit var store: SessionStore
    private val api = FutmondoApi(AndroidHttp())
    private val service = FutmondoService(api)

    private lateinit var loginBox: LinearLayout
    private lateinit var mainBox: LinearLayout
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var sellBox: LinearLayout
    private lateinit var sellButton: Button
    private val buttons = ArrayList<Button>()
    private val checks = ArrayList<Pair<CheckBox, SellCandidate>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        setContentView(buildUi())
        val s = store.load()
        if (s != null) {
            api.setSession(s)
            showMain(s.teamName)
        } else {
            showLogin()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        buttons.add(this)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val warning = text(
            "⚠ Esta app usa la API NO oficial de Futmondo. Puede dejar de funcionar en cualquier " +
                "momento y su uso podría ir contra las condiciones de uso de Futmondo. Úsala bajo tu responsabilidad.",
            12f,
        )
        root.addView(warning)

        loginBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        email = EditText(this).apply {
            hint = "Correo de Futmondo"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        password = EditText(this).apply {
            hint = "Contraseña (no se guarda)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        loginBox.addView(email)
        loginBox.addView(password)
        loginBox.addView(button("Entrar") { doLogin() })
        root.addView(loginBox)

        mainBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mainBox.addView(button("Analizar") { runAnalyze() })
        mainBox.addView(button("Revisar mercado") { runMarket() })
        mainBox.addView(button("Cerrar sesión") {
            store.clear(); api.setSession(null); result.text = ""; clearSells(); showLogin()
        })
        root.addView(mainBox)

        status = text("")
        root.addView(status)
        result = text("").apply { setTextIsSelectable(true) }
        root.addView(result)

        sellBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sellBox)
        sellButton = button("Poner a la venta los seleccionados") { confirmSell() }.apply { visibility = View.GONE }
        root.addView(sellButton)

        return ScrollView(this).apply { addView(root) }
    }

    private fun showLogin() {
        loginBox.visibility = View.VISIBLE
        mainBox.visibility = View.GONE
        status.text = ""
    }

    private fun showMain(team: String) {
        loginBox.visibility = View.GONE
        mainBox.visibility = View.VISIBLE
        status.text = if (team.isNotEmpty()) "Equipo: $team" else ""
    }

    private fun setBusy(busy: Boolean, msg: String = "") {
        buttons.forEach { it.isEnabled = !busy }
        if (busy) status.text = msg
    }

    /** Ejecuta trabajo de red fuera del hilo de UI y entrega el resultado en el hilo de UI. */
    private fun <T> background(msg: String, work: () -> T, done: (T) -> Unit) {
        setBusy(true, msg)
        Thread {
            val r = try { Result.success(work()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                setBusy(false)
                status.text = ""
                r.onSuccess(done).onFailure { showError(it) }
            }
        }.start()
    }

    private fun showError(e: Throwable) {
        val msg = when {
            e is FutmondoException && (e.code == "http.401" || e.code == "http.403" || e.code.contains("auth") || e.code.contains("token")) ->
                "La sesión ha caducado. Cierra sesión y vuelve a entrar."
            e is FutmondoException -> "Futmondo devolvió un error: ${e.code}"
            e is java.io.IOException -> "No se pudo conectar con Futmondo. Revisa tu conexión."
            else -> "Error inesperado (¿ha cambiado la API?): ${e.message}"
        }
        result.text = msg
        clearSells()
    }

    private fun doLogin() {
        val mail = email.text.toString().trim()
        val pwd = password.text.toString()
        if (mail.isEmpty() || pwd.isEmpty()) { status.text = "Escribe correo y contraseña."; return }
        background("Entrando…", { api.login(mail, pwd) }) { s ->
            store.save(s) // solo token e ids; la contraseña se descarta
            password.setText("")
            showMain(s.teamName)
        }
    }

    private fun runAnalyze() {
        clearSells()
        background("Analizando…", { Reports.analysis(service.analyze()) }) { result.text = it }
    }

    private fun runMarket() {
        clearSells()
        background("Revisando mercado…", { service.reviewMarket() }) { m ->
            result.text = Reports.market(m)
            showSellChoices(m)
        }
    }

    private fun clearSells() {
        sellBox.removeAllViews()
        checks.clear()
        sellButton.visibility = View.GONE
    }

    private fun showSellChoices(m: MarketResult) {
        if (m.sells.isEmpty()) return
        sellBox.addView(text("Elige qué poner a la venta (nada se vende hasta que confirmes):"))
        for (c in m.sells) {
            val cb = CheckBox(this).apply {
                text = "${c.player.name} – ${Reports.money(c.price)}"
                isChecked = c.preselected
            }
            checks.add(cb to c)
            sellBox.addView(cb)
        }
        sellButton.visibility = View.VISIBLE
    }

    private fun confirmSell() {
        val chosen = checks.filter { it.first.isChecked }.map { it.second }
        if (chosen.isEmpty()) { status.text = "No hay ningún jugador seleccionado."; return }
        val list = chosen.joinToString("\n") { "• ${it.player.name} a ${Reports.money(it.price)}" }
        AlertDialog.Builder(this)
            .setTitle("Confirmar: poner a la venta")
            .setMessage("Se pondrán a la venta (no venta directa) al valor de mercado:\n\n$list\n\nOtros usuarios y la máquina harán ofertas; tú decides si las aceptas en Futmondo.")
            .setPositiveButton("Poner a la venta") { _, _ -> doSell(chosen) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doSell(chosen: List<SellCandidate>) {
        background("Poniendo a la venta…", { service.putOnSale(chosen) }) { failures ->
            val ok = chosen.size - failures.size
            result.text = buildString {
                append("Puestos a la venta: $ok de ${chosen.size}.\n")
                failures.forEach { append("✗ ${it.first.player.name}: ${it.second}\n") }
            }
            clearSells()
        }
    }
}
