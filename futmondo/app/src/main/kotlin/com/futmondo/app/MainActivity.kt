package com.futmondo.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Build
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
import com.futmondo.core.History
import com.futmondo.core.Reports
import com.futmondo.core.SellCandidate

/**
 * Botones: Analizar, Revisar mercado, Historial, Precisión y revisión diaria.
 * La app solo PONE A LA VENTA, y únicamente lo que el usuario marca y confirma. No puja nunca,
 * no hace venta directa, no responde ofertas y no cambia la alineación. La revisión diaria solo avisa.
 */
class MainActivity : Activity() {
    private lateinit var store: SessionStore
    private lateinit var controller: Controller
    private val api = FutmondoApi(AndroidHttp())

    private lateinit var loginBox: LinearLayout
    private lateinit var mainBox: LinearLayout
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var sellBox: LinearLayout
    private lateinit var sellButton: Button
    private lateinit var dailyCheck: CheckBox
    private lateinit var dailyHourButton: Button
    private val buttons = ArrayList<Button>()
    private val checks = ArrayList<Pair<CheckBox, SellCandidate>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        controller = Controller(this, api)
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
        root.addView(
            text(
                "⚠ Esta app usa la API NO oficial de Futmondo. Puede dejar de funcionar en cualquier " +
                    "momento y su uso podría ir contra las condiciones de uso de Futmondo. Úsala bajo tu responsabilidad.",
                12f,
            ),
        )

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
        mainBox.addView(button("Historial") { clearSells(); result.text = History.format(controller.data.history()) })
        mainBox.addView(button("Precisión del modelo") { runPrecision() })

        dailyCheck = CheckBox(this).apply {
            text = "Revisión diaria automática (solo avisa, no cambia nada)"
            isChecked = controller.data.dailyEnabled
            setOnCheckedChangeListener { _, on -> setDaily(on) }
        }
        mainBox.addView(dailyCheck)
        dailyHourButton = button("") { pickHour() }
        updateHourLabel()
        mainBox.addView(dailyHourButton)

        mainBox.addView(button("Borrar historial") {
            controller.data.clearHistory(); clearSells(); result.text = "Historial borrado."
        })
        mainBox.addView(button("Cerrar sesión") {
            store.clear(); api.setSession(null); result.text = ""; clearSells()
            controller.data.dailyEnabled = false
            dailyCheck.isChecked = false
            DailyReview.cancel(this)
            showLogin()
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

    private fun updateHourLabel() {
        dailyHourButton.text = "Hora de la revisión diaria: %02d:00".format(controller.data.dailyHour)
    }

    private fun setDaily(on: Boolean) {
        controller.data.dailyEnabled = on
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
            }
            DailyReview.schedule(this, controller.data.dailyHour)
            status.text = "Revisión diaria activada. Android puede retrasarla unos minutos; " +
                "desactiva la optimización de batería para esta app si quieres más puntualidad."
        } else {
            DailyReview.cancel(this)
            status.text = "Revisión diaria desactivada."
        }
    }

    private fun pickHour() {
        TimePickerDialog(this, { _, h, _ ->
            controller.data.dailyHour = h
            updateHourLabel()
            if (controller.data.dailyEnabled) DailyReview.schedule(this, h)
        }, controller.data.dailyHour, 0, true).show()
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
            e is FutmondoException && e.isAuth -> "La sesión ha caducado. Cierra sesión y vuelve a entrar."
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
        background("Analizando…", { controller.analyze() }) { result.text = Reports.analysis(it) }
    }

    private fun runMarket() {
        clearSells()
        background("Revisando mercado…", { controller.reviewMarket() }) { m ->
            result.text = Reports.market(m)
            if (m.sells.isEmpty()) return@background
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
    }

    private fun runPrecision() {
        clearSells()
        background("Calculando precisión…", { controller.precisionReport() }) { result.text = it }
    }

    private fun clearSells() {
        sellBox.removeAllViews()
        checks.clear()
        sellButton.visibility = View.GONE
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
        background("Poniendo a la venta…", { controller.putOnSale(chosen) }) { failures ->
            val ok = chosen.size - failures.size
            result.text = buildString {
                append("Puestos a la venta: $ok de ${chosen.size}.\n")
                failures.forEach { append("✗ ${it.first.player.name}: ${it.second}\n") }
            }
            clearSells()
        }
    }
}
