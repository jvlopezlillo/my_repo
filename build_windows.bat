@echo off
REM Genera dist\PDFTextEdit\PDFTextEdit.exe (requiere Python 3.10+ en Windows)
python -m venv .venv && call .venv\Scripts\activate
pip install -r requirements.txt pyinstaller
pyinstaller --noconfirm --windowed --name PDFTextEdit --collect-all pymupdf --hidden-import fontTools.ttLib run_pdftextedit.py
echo.
echo Listo: dist\PDFTextEdit\PDFTextEdit.exe
