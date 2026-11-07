"""Interfaz sencilla (tkinter) para backup y restauración."""
from __future__ import annotations

import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext, ttk

from . import core
from .adb import Adb, AdbError, download_platform_tools, find_adb

HELP = (
    "Antes de empezar:\n"
    "1. En el móvil: Ajustes > Acerca del teléfono > pulsa 7 veces 'Versión MIUI' (modo desarrollador).\n"
    "2. Ajustes adicionales > Opciones de desarrollador: activa 'Depuración USB' y "
    "'Instalar mediante USB' (y 'Depuración USB (ajustes de seguridad)').\n"
    "3. En WhatsApp: Ajustes > Chats > Copia de seguridad > COPIAR AHORA (para un backup al día).\n"
    "4. Conecta el cable y acepta el aviso 'Permitir depuración USB'."
)


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("WhatsApp Backup & Restore (Android)")
        self.geometry("760x560")
        self.q: queue.Queue[str] = queue.Queue()
        self.legacy = tk.StringVar()
        self.busy = False

        ttk.Label(self, text=HELP, justify="left", wraplength=730).pack(padx=10, pady=8, anchor="w")
        row = ttk.Frame(self); row.pack(fill="x", padx=10)
        self.btn_adb = ttk.Button(row, text="Descargar ADB", command=lambda: self.run(self.do_adb))
        self.btn_b = ttk.Button(row, text="Hacer BACKUP…", command=self.on_backup)
        self.btn_r = ttk.Button(row, text="RESTAURAR…", command=self.on_restore)
        for b in (self.btn_adb, self.btn_b, self.btn_r):
            b.pack(side="left", padx=4, pady=6)

        adv = ttk.LabelFrame(self, text="Avanzado (opcional): APK antiguo de WhatsApp para guardar también los datos privados")
        adv.pack(fill="x", padx=10, pady=4)
        ttk.Entry(adv, textvariable=self.legacy).pack(side="left", fill="x", expand=True, padx=4, pady=4)
        ttk.Button(adv, text="Elegir APK…", command=lambda: self.legacy.set(
            filedialog.askopenfilename(filetypes=[("APK", "*.apk")]) or self.legacy.get())).pack(side="left", padx=4)

        self.log = scrolledtext.ScrolledText(self, height=18, state="disabled")
        self.log.pack(fill="both", expand=True, padx=10, pady=8)
        self.after(100, self.poll)
        self.write("ADB: " + (find_adb() or "no encontrado (pulsa 'Descargar ADB')"))

    # --- utilidades ---
    def write(self, msg: str):
        self.q.put(msg)

    def poll(self):
        while not self.q.empty():
            self.log.configure(state="normal")
            self.log.insert("end", self.q.get() + "\n")
            self.log.see("end")
            self.log.configure(state="disabled")
        self.after(100, self.poll)

    def run(self, fn, *a):
        if self.busy:
            return
        self.busy = True

        def work():
            try:
                fn(*a)
            except Exception as e:  # noqa: BLE001
                self.write(f"ERROR: {e}")
            finally:
                self.busy = False
        threading.Thread(target=work, daemon=True).start()

    def legacy_path(self):
        return Path(self.legacy.get()) if self.legacy.get() else None

    # --- acciones ---
    def do_adb(self):
        self.write("Descargando platform-tools de Google…")
        self.write("ADB listo: " + download_platform_tools())

    def on_backup(self):
        d = filedialog.askdirectory(title="Carpeta VACÍA donde guardar el backup")
        if d:
            self.run(lambda: core.backup(Adb(), Path(d), self.write, self.legacy_path()))

    def on_restore(self):
        d = filedialog.askdirectory(title="Carpeta del backup a restaurar")
        if d and messagebox.askyesno("Confirmar", "Usa esto con WhatsApp recién reseteado y SIN abrir.\n¿Continuar?"):
            self.run(lambda: core.restore(Adb(), Path(d), self.write, self.legacy_path()))


def main():
    App().mainloop()
