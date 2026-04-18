package com.example.routeon

import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * 차량 설정 화면
 * fuel_type  저장값: 0=휘발유, 1=고급휘발유, 2=경유, 3=LPG, 4=전기, 5=하이브리드, 6=플러그인HEV, 7=수소
 * car_type   저장값: 0=1종소형, 1=2종중형, 2=3종대형, 3=4종대형화물, 4=5종특수화물, 5=6종경차, 6=이륜차
 */
class VehicleSettingsActivity : AppCompatActivity() {

    private val carTypeItems by lazy {
        listOf(
            Triple(R.id.carType1,    R.id.radioCarType1,    0),
            Triple(R.id.carType2,    R.id.radioCarType2,    1),
            Triple(R.id.carType3,    R.id.radioCarType3,    2),
            Triple(R.id.carType4,    R.id.radioCarType4,    3),
            Triple(R.id.carType5,    R.id.radioCarType5,    4),
            Triple(R.id.carType6,    R.id.radioCarType6,    5),
            Triple(R.id.carTypeBike, R.id.radioCarTypeBike, 6)
        )
    }

    private val fuelItems by lazy {
        listOf(
            Triple(R.id.fuelGasoline,        R.id.radioFuelGasoline,        0),
            Triple(R.id.fuelPremiumGasoline,  R.id.radioFuelPremiumGasoline,  1),
            Triple(R.id.fuelDiesel,          R.id.radioFuelDiesel,          2),
            Triple(R.id.fuelLPG,             R.id.radioFuelLPG,             3),
            Triple(R.id.fuelElectric,        R.id.radioFuelElectric,        4),
            Triple(R.id.fuelHybrid,          R.id.radioFuelHybrid,          5),
            Triple(R.id.fuelPlugInHybrid,    R.id.radioFuelPlugInHybrid,    6),
            Triple(R.id.fuelHydrogen,        R.id.radioFuelHydrogen,        7)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val savedCarType  = prefs.getInt("car_type", 0)
        val savedFuelType = prefs.getInt("fuel_type", 0)

        // 차량 종류
        carTypeItems.forEach { (layoutId, radioId, value) ->
            val row   = findViewById<LinearLayout>(layoutId)
            val radio = findViewById<RadioButton>(radioId)
            radio.isChecked = (value == savedCarType)
            row.setOnClickListener {
                prefs.edit().putInt("car_type", value).apply()
                carTypeItems.forEach { (_, rid, _) ->
                    findViewById<RadioButton>(rid).isChecked = (rid == radioId)
                }
            }
        }

        // 연료 종류
        fuelItems.forEach { (layoutId, radioId, value) ->
            val row   = findViewById<LinearLayout>(layoutId)
            val radio = findViewById<RadioButton>(radioId)
            radio.isChecked = (value == savedFuelType)
            row.setOnClickListener {
                prefs.edit().putInt("fuel_type", value).apply()
                fuelItems.forEach { (_, rid, _) ->
                    findViewById<RadioButton>(rid).isChecked = (rid == radioId)
                }
            }
        }
    }
}
