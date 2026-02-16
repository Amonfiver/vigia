package com.minda.vigilante.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class TrainingStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("vigia_training", Context.MODE_PRIVATE)

    // roiName -> label -> samples
    private val data: MutableMap<String, MutableMap<TrainLabel, MutableList<Features>>> = mutableMapOf()

    fun load() {
        data.clear()
        val raw = prefs.getString("json", null) ?: return
        val root = JSONObject(raw)

        for (roiKey in root.keys()) {
            val roiObj = root.getJSONObject(roiKey)
            val map = mutableMapOf<TrainLabel, MutableList<Features>>()

            for (label in TrainLabel.values()) {
                val arr = roiObj.optJSONArray(label.name) ?: JSONArray()
                val list = mutableListOf<Features>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Features(
                            orangeRatio = o.optDouble("orange", 0.0).toFloat(),
                            topRedRatio = o.optDouble("topRed", 0.0).toFloat(),
                            bottomRedRatio = o.optDouble("botRed", 0.0).toFloat(),
                        )
                    )
                }
                map[label] = list
            }
            data[roiKey] = map
        }
    }

    fun save() {
        val root = JSONObject()
        for ((roi, labelMap) in data) {
            val roiObj = JSONObject()
            for (label in TrainLabel.values()) {
                val arr = JSONArray()
                val list = labelMap[label] ?: emptyList()
                for (f in list) {
                    val o = JSONObject()
                    o.put("orange", f.orangeRatio)
                    o.put("topRed", f.topRedRatio)
                    o.put("botRed", f.bottomRedRatio)
                    arr.put(o)
                }
                roiObj.put(label.name, arr)
            }
            root.put(roi, roiObj)
        }
        prefs.edit().putString("json", root.toString()).apply()
    }

    fun clearAll() {
        data.clear()
        prefs.edit().remove("json").apply()
    }

    fun addSample(roi: String, label: TrainLabel, f: Features) {
        val lm = data.getOrPut(roi) { mutableMapOf() }
        val list = lm.getOrPut(label) { mutableListOf() }
        list.add(f)
        save()
    }

    fun counts(roi: String): Map<TrainLabel, Int> {
        val lm = data[roi]
        val out = mutableMapOf<TrainLabel, Int>()
        for (l in TrainLabel.values()) {
            out[l] = lm?.get(l)?.size ?: 0
        }
        return out
    }

    fun isTrained(roi: String, minPerLabel: Int = 3): Boolean {
        val c = counts(roi)
        return (c[TrainLabel.OK] ?: 0) >= minPerLabel &&
                (c[TrainLabel.OBSTACLE] ?: 0) >= minPerLabel &&
                (c[TrainLabel.FAULT] ?: 0) >= minPerLabel
    }

    data class Thresholds(
        val orangeThreshold: Float,
        val redThreshold: Float
    )

    /**
     * Umbrales auto-calculados:
     * - orangeThreshold: punto medio entre OK y OBSTACLE (orangeRatio)
     * - redThreshold: punto medio entre OK y FAULT (top/bot redRatio)
     */
    fun computeThresholds(roi: String): Thresholds? {
        val lm = data[roi] ?: return null
        val ok = lm[TrainLabel.OK].orEmpty()
        val ob = lm[TrainLabel.OBSTACLE].orEmpty()
        val fl = lm[TrainLabel.FAULT].orEmpty()

        if (ok.isEmpty() || ob.isEmpty() || fl.isEmpty()) return null

        val okOrange = ok.map { it.orangeRatio }.average().toFloat()
        val obOrange = ob.map { it.orangeRatio }.average().toFloat()

        val okRed = ok.map { max(it.topRedRatio, it.bottomRedRatio) }.average().toFloat()
        val flRed = fl.map { min(it.topRedRatio, it.bottomRedRatio) }.average().toFloat()

        val orangeTh = (okOrange + obOrange) / 2f
        val redTh = (okRed + flRed) / 2f

        return Thresholds(
            orangeThreshold = orangeTh.coerceIn(0.05f, 0.95f),
            redThreshold = redTh.coerceIn(0.05f, 0.95f)
        )
    }
}
