"""Genera PDFs de prueba: fuentes TTF incrustadas (subconjunto) y fuentes base-14."""
import sys
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

LIB = "/usr/share/fonts/truetype/liberation/"

def make(path):
    pdfmetrics.registerFont(TTFont("LibSerif", LIB + "LiberationSerif-Regular.ttf"))
    pdfmetrics.registerFont(TTFont("LibSerifB", LIB + "LiberationSerif-Bold.ttf"))
    pdfmetrics.registerFont(TTFont("LibSansI", LIB + "LiberationSans-Italic.ttf"))
    c = canvas.Canvas(path, pagesize=(400, 300))
    c.setFont("LibSerif", 14); c.setFillColorRGB(0, 0, 0.6)
    c.drawString(40, 250, "Hello world from a PDF")
    c.setFont("LibSerifB", 18); c.setFillColorRGB(0.6, 0, 0)
    c.drawString(40, 210, "Bold title here")
    c.setFont("LibSansI", 12); c.setFillColorRGB(0, 0, 0)
    c.drawString(40, 170, "Italic sans line")
    c.setFont("Helvetica", 12)
    c.drawString(40, 130, "Standard Helvetica text")
    c.save()

if __name__ == "__main__":
    make(sys.argv[1])
