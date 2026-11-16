"""Punto de entrada para PyInstaller / ejecución directa (usa importaciones absolutas)."""
import multiprocessing

from pdftextedit.gui import main

if __name__ == "__main__":
    multiprocessing.freeze_support()
    main()
