// Sundial.kt
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.google.gson.GsonBuilder
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter

class Sundial {
    @Parameter(names = ["--lat"], required = true)
    private var lat: Double = 0.0

    @Parameter(names = ["--lon"], required = true)
    private var lon: Double = 0.0

    @Parameter(names = ["--date"])
    private var dateStr: String? = null

    @Parameter(names = ["--time"])
    private var timeStr: String? = null

    @Parameter(names = ["--height"])
    private var height: Double = 1.0

    @Parameter(names = ["--tz"])
    private var tzOffset: Double? = null

    @Parameter(names = ["--shadow"])
    private var showShadow: Boolean = false

    @Parameter(names = ["--day"])
    private var dayMode: Boolean = false

    @Parameter(names = ["--json"])
    private var jsonFile: String? = null

    @Parameter(names = ["--csv"])
    private var csvFile: String? = null

    private val DEG = Math.PI / 180.0
    private val RAD = 180.0 / Math.PI
    private lateinit var dateTime: ZonedDateTime
    private lateinit var utcTime: ZonedDateTime

    data class Result(
        val altitude: Double,
        val azimuth: Double,
        val shadowLen: Double?,
        val shadowAz: Double,
        val solarTime: Double,
        val dec: Double,
        val hourAngle: Double,
        val aboveHorizon: Boolean
    )

    fun init() {
        val zone = ZoneId.systemDefault()
        var dt = ZonedDateTime.now(zone)
        if (dateStr != null) {
            val d = LocalDate.parse(dateStr)
            dt = dt.withYear(d.year).withMonth(d.monthValue).withDayOfMonth(d.dayOfMonth)
        }
        if (timeStr != null) {
            val parts = timeStr!!.split(':')
            val h = parts[0].toInt()
            val m = if (parts.size > 1) parts[1].toInt() else 0
            val s = if (parts.size > 2) parts[2].toInt() else 0
            dt = dt.withHour(h).withMinute(m).withSecond(s)
        }
        dateTime = dt
        val tz = tzOffset ?: -(zone.rules.getOffset(dt.toInstant()).totalSeconds / 3600.0)
        utcTime = dt.minusSeconds((tz * 3600).toLong())
    }

    private fun julianDay(dt: ZonedDateTime): Double {
        var y = dt.year
        var m = dt.monthValue
        var d = dt.dayOfMonth.toDouble() + dt.hour / 24.0 + dt.minute / 1440.0 + dt.second / 86400.0
        if (m <= 2) { y--; m += 12 }
        val A = y / 100
        val B = 2 - A + A / 4
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + B - 1524.5
    }

    private fun solarDeclination(dt: ZonedDateTime): Double {
        val start = ZonedDateTime.of(dt.year, 1, 1, 0, 0, 0, 0, dt.zone)
        val n = Duration.between(start, dt).toDays().toDouble() + 1
        return -23.44 * Math.cos(DEG * (360.0 / 365.0) * (n + 10))
    }

    private fun equationOfTime(dt: ZonedDateTime): Double {
        val start = ZonedDateTime.of(dt.year, 1, 1, 0, 0, 0, 0, dt.zone)
        val n = Duration.between(start, dt).toDays().toDouble() + 1
        val B = (360.0 / 365.0) * (n - 81)
        val B_rad = B * DEG
        return 9.87 * Math.sin(2 * B_rad) - 7.53 * Math.cos(B_rad) - 1.5 * Math.sin(B_rad)
    }

    fun calculate(): Result {
        val eot = equationOfTime(utcTime)
        val utcHours = utcTime.hour + utcTime.minute / 60.0 + utcTime.second / 3600.0
        val solarTime = utcHours + lon / 15.0 + eot / 60.0
        val hourAngle = (solarTime - 12) * 15 * DEG
        val dec = solarDeclination(utcTime) * DEG
        val latRad = lat * DEG
        val sinAlt = Math.sin(latRad) * Math.sin(dec) + Math.cos(latRad) * Math.cos(dec) * Math.cos(hourAngle)
        val altitude = Math.asin(sinAlt)
        var azimuth = 0.0
        if (altitude > 0.01) {
            val cosAz = (Math.sin(dec) - Math.sin(latRad) * sinAlt) / (Math.cos(latRad) * Math.cos(altitude) + 1e-9)
            azimuth = Math.acos(cosAz.coerceIn(-1.0, 1.0))
            if (hourAngle > 0) azimuth = -azimuth + 2 * Math.PI
            azimuth = (azimuth + 2 * Math.PI) % (2 * Math.PI)
        }
        val shadowLen = if (altitude > 0.01) height / Math.tan(altitude) else null
        val shadowAz = if (altitude > 0.01) (azimuth + Math.PI) % (2 * Math.PI) * RAD else 0.0
        return Result(
            altitude = altitude * RAD,
            azimuth = azimuth * RAD,
            shadowLen = shadowLen,
            shadowAz = shadowAz,
            solarTime = solarTime,
            dec = dec * RAD,
            hourAngle = hourAngle * RAD,
            aboveHorizon = altitude > 0
        )
    }

    fun printResult(res: Result) {
        val color = System.console() != null
        val c = if (color) "\u001B[36m" else ""
        val cY = if (color) "\u001B[33m" else ""
        val cG = if (color) "\u001B[32m" else ""
        val cB = if (color) "\u001B[34m" else ""
        val cR = if (color) "\u001B[31m" else ""
        val reset = if (color) "\u001B[0m" else ""
        println("${c}☀️ Расчёт солнечной тени:${reset}")
        println("  ${cY}Дата/время:${reset} ${dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
        println("  ${cY}Широта:${reset} ${"%.4f".format(lat)}°, Долгота: ${"%.4f".format(lon)}°")
        println("  ${cY}Высота гномона:${reset} ${"%.2f".format(height)} м")
        if (res.aboveHorizon) {
            println("  ${cG}Высота Солнца:${reset} ${"%.2f".format(res.altitude)}°")
            println("  ${cG}Азимут Солнца:${reset} ${"%.2f".format(res.azimuth)}°")
            if (res.shadowLen != null) {
                println("  ${cB}Длина тени:${reset} ${"%.2f".format(res.shadowLen)} м")
            } else {
                println("  ${c}Тень бесконечна (Солнце в зените)${reset}")
            }
            println("  ${cB}Направление тени:${reset} ${"%.2f".format(res.shadowAz)}°")
            if (showShadow) drawShadow(res)
        } else {
            println("${cR}  Солнце под горизонтом (ночь)${reset}")
        }
    }

    private fun drawShadow(res: Result) {
        val size = 15
        val center = size / 2
        val scale = 2.0
        val shadowLen = if (res.shadowLen != null) (res.shadowLen!!.coerceAtMost(10.0) / scale) else 0.0
        val shadowAz = res.shadowAz * DEG
        val x = (center + shadowLen * Math.sin(shadowAz)).toInt()
        val y = (center - shadowLen * Math.cos(shadowAz)).toInt()
        val grid = Array(size) { CharArray(size) { '░' } }
        grid[center][center] = '╋'
        if (x in 0 until size && y in 0 until size && res.shadowLen != null) {
            val steps = maxOf(Math.abs(x - center), Math.abs(y - center))
            for (i in 0..steps) {
                val cx = (center + (x - center) * i.toDouble() / steps).toInt()
                val cy = (center + (y - center) * i.toDouble() / steps).toInt()
                if (cx in 0 until size && cy in 0 until size && grid[cy][cx] == '░') {
                    grid[cy][cx] = '█'
                }
            }
        }
        println("\n\u001B[36mВид сверху (N ↑):\u001B[0m")
        print("  ")
        for (i in 0 until size) print("%2d".format(i))
        println()
        for (i in 0 until size) {
            print("%2d ".format(i))
            for (j in 0 until size) {
                when (grid[i][j]) {
                    '█' -> print("\u001B[32m█\u001B[0m")
                    '╋' -> print("\u001B[37m╋\u001B[0m")
                    else -> print("\u001B[34m░\u001B[0m")
                }
            }
            println()
        }
    }

    fun exportJson(res: Result) {
        val data = mapOf(
            "time" to dateTime.toString(),
            "lat" to lat,
            "lon" to lon,
            "height" to height,
            "result" to res
        )
        val gson = GsonBuilder().setPrettyPrinting().create()
        File(jsonFile).writeText(gson.toJson(data))
        println("\u001B[32mЭкспортировано в $jsonFile\u001B[0m")
    }

    fun exportCsv(res: Result) {
        File(csvFile).printWriter().use { pw ->
            pw.println("time,altitude,azimuth,shadow_len,shadow_az")
            pw.println("${dateTime},${"%.2f".format(res.altitude)},${"%.2f".format(res.azimuth)},${res.shadowLen},${"%.2f".format(res.shadowAz)}")
        }
        println("\u001B[32mЭкспортировано в $csvFile\u001B[0m")
    }

    fun daySeries(): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        var dt = dateTime.withHour(0).withMinute(0).withSecond(0)
        while (dt.hour < 24) {
            val tz = tzOffset ?: -(dateTime.offset.totalSeconds / 3600.0)
            val utc = dt.minusSeconds((tz * 3600).toLong())
            val tmp = Sundial().apply {
                lat = this@Sundial.lat
                lon = this@Sundial.lon
                height = this@Sundial.height
                tzOffset = tz
                dateTime = dt
                utcTime = utc
            }
            val res = tmp.calculate()
            results.add(mapOf(
                "time" to dt,
                "altitude" to res.altitude,
                "azimuth" to res.azimuth,
                "shadow_len" to res.shadowLen,
                "shadow_az" to res.shadowAz,
                "above_horizon" to res.aboveHorizon
            ))
            dt = dt.plusHours(1)
        }
        return results
    }

    fun run() {
        init()
        if (dayMode) {
            val results = daySeries()
            println("\u001B[36m📊 Тень на ${dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE)} (почасово):\u001B[0m")
            for (r in results) {
                if (r["above_horizon"] as Boolean) {
                    val shadowLen = if (r["shadow_len"] != null) "%6.2f".format(r["shadow_len"]) else "     ∞"
                    println("  ${(r["time"] as ZonedDateTime).format(DateTimeFormatter.ofPattern("HH:mm"))}  Alt: %5.1f°  Тень: $shadowLen м  Направление: %6.1f°".format(
                        r["altitude"], r["shadow_az"]))
                } else {
                    println("  ${(r["time"] as ZonedDateTime).format(DateTimeFormatter.ofPattern("HH:mm"))}  Ночь")
                }
            }
            jsonFile?.let {
                val gson = GsonBuilder().setPrettyPrinting().create()
                File(it).writeText(gson.toJson(results))
                println("Экспортировано в $it")
            }
            csvFile?.let {
                File(it).printWriter().use { pw ->
                    pw.println("time,altitude,azimuth,shadow_len,shadow_az")
                    for (r in results) {
                        pw.println("${r["time"]},${r["altitude"]},${r["azimuth"]},${r["shadow_len"]},${r["shadow_az"]}")
                    }
                }
                println("Экспортировано в $it")
            }
        } else {
            val res = calculate()
            printResult(res)
            jsonFile?.let { exportJson(res) }
            csvFile?.let { exportCsv(res) }
        }
    }
}

fun main(args: Array<String>) {
    val sundial = Sundial()
    JCommander.newBuilder().addObject(sundial).build().parse(*args)
    sundial.run()
}
