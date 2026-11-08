package com.futmondo.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.futmondo.core.Compare
import com.futmondo.core.History
import com.futmondo.core.Line
import com.futmondo.core.Section
import com.futmondo.core.Tone
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
    private lateinit var resultBox: LinearLayout
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
        mainBox.addView(button("Comparar jugadores") { runCompare() })
        mainBox.addView(button("Historial") { clearSells(); showText(History.format(controller.data.history())) })
        mainBox.addView(button("Precisión del modelo") { runPrecision() })

        mainBox.addView(text("Avisos automáticos (solo avisan, no cambian nada):", 13f))
        dailyCheck = CheckBox(this).apply {
            text = "Revisión diaria y aviso antes del cierre de alineación"
            isChecked = controller.data.dailyEnabled
            setOnCheckedChangeListener { _, on -> setDaily(on) }
        }
        mainBox.addView(dailyCheck)
        dailyHourButton = button("") { pickHour() }
        updateHourLabel()
        mainBox.addView(dailyHourButton)

        mainBox.addView(text("Datos:", 13f))
        mainBox.addView(button("Exportar copia de seguridad") { exportBackup() })
        mainBox.addView(button("Importar copia de seguridad") { importBackup() })
        mainBox.addView(button("Borrar historial") {
            controller.data.clearHistory(); clearSells(); showText("Historial borrado.")
        })
        mainBox.addView(button("Cerrar sesión") {
            store.clear(); api.setSession(null); resultBox.removeAllViews(); clearSells()
            controller.data.dailyEnabled = false
            dailyCheck.isChecked = false
            DailyReview.cancel(this)
            showLogin()
        })
        root.addView(mainBox)

        status = text("")
        root.addView(status)
        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultBox)

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
        val msg = when (e) {
            is FutmondoException -> e.userMessage
            is java.io.IOException -> "No se pudo conectar con Futmondo. Revisa tu conexión."
            else -> "Error inesperado (¿ha cambiado la API?): ${e.message}"
        }
        showSections(listOf(Section("", listOf(Line(msg, Tone.BAD)))))
        clearSells()
    }

    private val toneColors = mapOf(
        Tone.NORMAL to Color.parseColor("#1B2A41"), Tone.GOOD to Color.parseColor("#2E7D32"),
        Tone.WARN to Color.parseColor("#B45309"), Tone.BAD to Color.parseColor("#C62828"),
        Tone.MUTED to Color.parseColor("#6B7280"),
    )

    private fun card(sec: Section): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(Color.parseColor("#F2F4F7")); cornerRadius = dp(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(6), 0, dp(6)) }
        if (sec.title.isNotEmpty()) {
            addView(TextView(context).apply {
                text = sec.title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(toneColors.getValue(Tone.NORMAL))
                setTextIsSelectable(true)
            })
        }
        sec.lines.forEach { l ->
            addView(TextView(context).apply {
                text = l.text
                textSize = 14f
                setTextColor(toneColors.getValue(l.tone))
                setPadding(0, dp(3), 0, dp(3))
                setTextIsSelectable(true)
            })
        }
    }

    private fun showSections(sections: List<Section>) {
        resultBox.removeAllViews()
        sections.filter { it.title.isNotEmpty() || it.lines.isNotEmpty() }.forEach { resultBox.addView(card(it)) }
    }

    private fun showText(t: String) = showSections(listOf(Section("", t.split("\n\n").map { Line(it) })))

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
        background("Analizando…", { controller.analyze() }) { showSections(Reports.analysisSections(it)) }
    }

    private fun runMarket() {
        clearSells()
        background("Revisando mercado…", { controller.reviewMarket() }) { m ->
            showSections(Reports.marketSections(m))
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
        background("Calculando precisión…", { controller.precisionReport() }) { showText(it) }
    }

    private fun runCompare() {
        clearSells()
        background("Cargando jugadores…", { controller.comparisonPool() }) { pool ->
            val sorted = pool.sortedWith(compareBy({ it.player.role.ordinal }, { it.player.name }))
            val labels = sorted.map { "${it.player.name} (${it.player.role.label}, ${it.player.team})" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Primer jugador").setItems(labels) { _, i ->
                AlertDialog.Builder(this).setTitle("Segundo jugador").setItems(labels) { _, j ->
                    if (i == j) status.text = "Elige dos jugadores distintos."
                    else showSections(Compare.sections(sorted[i], sorted[j]))
                }.show()
            }.show()
        }
    }

    private fun exportBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "futmondo-copia.json")
        }
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> background("Guardando copia…", {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(controller.exportBackup().toByteArray(Charsets.UTF_8)) }
            }) { showText("Copia guardada. No contiene token ni contraseña.") }
            REQ_IMPORT -> background("Importando copia…", {
                controller.importBackup(contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() })
            }) { ok -> showText(if (ok) "Copia importada y fusionada con tus datos." else "El archivo no es una copia válida de esta app.") }
        }
    }

    private companion object {
        const val REQ_EXPORT = 101
        const val REQ_IMPORT = 102
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
            showSections(listOf(Section("", listOf(Line("Puestos a la venta: $ok de ${chosen.size}.", if (failures.isEmpty()) Tone.GOOD else Tone.WARN)) +
                failures.map { Line("✗ ${it.first.player.name}: ${it.second}", Tone.BAD) })))
            clearSells()
        }
    }
}
