// Archivo build raíz. La configuración común se declara aquí; cada módulo tiene el suyo.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
