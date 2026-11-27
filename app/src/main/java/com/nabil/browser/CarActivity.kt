package com.nabil.browser

/** Same browser, always starts in car mode. Gets its own launcher icon for head units. */
class CarActivity : MainActivity() {
    override val startInCar: Boolean get() = true
}
