package com.limpiador.app.scan

enum class JunkCategory(val title: String, val description: String) {
    APP_CACHE(
        "Caché de aplicaciones",
        "Archivos temporales que las apps vuelven a crear cuando los necesitan."
    ),
    TEMP(
        "Temporales y registros",
        "Archivos .tmp, .log, LOST.DIR y restos de otros sistemas."
    ),
    THUMBNAILS(
        "Miniaturas",
        "Vistas previas de imágenes. La galería las regenera automáticamente."
    ),
    APK(
        "Instaladores APK",
        "Paquetes de instalación descargados. Se marcan los que ya están instalados."
    ),
    EMPTY_FOLDERS(
        "Carpetas vacías",
        "Carpetas sin contenido, normalmente de apps desinstaladas."
    ),
    DUPLICATES(
        "Archivos duplicados",
        "Copias idénticas. Siempre se conserva el archivo más antiguo."
    ),
    LARGE_FILES(
        "Archivos grandes",
        "Archivos de más de 50 MB. Revísalos antes de borrarlos."
    ),
}

data class JunkItem(
    val path: String,
    val name: String,
    val size: Long,
    val category: JunkCategory,
    val detail: String?,
    /** Si se marca por defecto para limpiar. */
    val recommended: Boolean,
    val isDirectory: Boolean,
)

data class JunkGroup(val category: JunkCategory, val items: List<JunkItem>) {
    val totalSize: Long get() = items.sumOf { it.size }
}

data class ScanProgress(val filesScanned: Int = 0, val currentPath: String = "")
