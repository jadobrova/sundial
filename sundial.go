// sundial.go
package main

import (
	"encoding/csv"
	"encoding/json"
	"flag"
	"fmt"
	"math"
	"os"
	"strconv"
	"time"
)

const DEG = math.Pi / 180
const RAD = 180 / math.Pi

type Sundial struct {
	lat      float64
	lon      float64
	height   float64
	tzOffset float64
	date     time.Time
	utcTime  time.Time
}

type Result struct {
	Altitude   float64 `json:"altitude"`
	Azimuth    float64 `json:"azimuth"`
	ShadowLen  *float64 `json:"shadow_len"`
	ShadowAz   float64 `json:"shadow_az"`
	SolarTime  float64 `json:"solar_time"`
	Dec        float64 `json:"dec"`
	HourAngle  float64 `json:"hour_angle"`
	AboveHorizon bool `json:"above_horizon"`
}

func NewSundial(lat, lon float64, dateStr, timeStr string, height float64, tzOffset *float64) *Sundial {
	s := &Sundial{lat: lat * DEG, lon: lon, height: height}
	if tzOffset != nil {
		s.tzOffset = *tzOffset
	} else {
		_, offset := time.Now().Zone()
		s.tzOffset = -float64(offset) / 3600
	}
	if dateStr != "" {
		t, _ := time.Parse("2006-01-02", dateStr)
		s.date = t
	} else {
		s.date = time.Now()
	}
	if timeStr != "" {
		var h, m, sec int
		fmt.Sscanf(timeStr, "%d:%d:%d", &h, &m, &sec)
		s.date = time.Date(s.date.Year(), s.date.Month(), s.date.Day(), h, m, sec, 0, time.Local)
	}
	s.utcTime = s.date.Add(-time.Duration(s.tzOffset * float64(time.Hour)))
	return s
}

func (s *Sundial) julianDay(t time.Time) float64 {
	y := t.Year()
	m := int(t.Month())
	d := float64(t.Day()) + float64(t.Hour())/24 + float64(t.Minute())/1440 + float64(t.Second())/86400
	if m <= 2 {
		y--
		m += 12
	}
	A := y / 100
	B := 2 - A + A/4
	return float64(int(365.25*float64(y+4716))+int(30.6001*float64(m+1))) + d + float64(B) - 1524.5
}

func (s *Sundial) solarDeclination(t time.Time) float64 {
	start := time.Date(t.Year(), 1, 1, 0, 0, 0, 0, time.UTC)
	n := float64(int(t.Sub(start).Hours()/24)) + 1
	return -23.44 * math.Cos(DEG*(360/365)*(n+10))
}

func (s *Sundial) equationOfTime(t time.Time) float64 {
	start := time.Date(t.Year(), 1, 1, 0, 0, 0, 0, time.UTC)
	n := float64(int(t.Sub(start).Hours()/24)) + 1
	B := (360 / 365) * (n - 81)
	B_rad := B * DEG
	return 9.87*math.Sin(2*B_rad) - 7.53*math.Cos(B_rad) - 1.5*math.Sin(B_rad)
}

func (s *Sundial) Calculate() Result {
	eot := s.equationOfTime(s.utcTime)
	utcHours := float64(s.utcTime.Hour()) + float64(s.utcTime.Minute())/60 + float64(s.utcTime.Second())/3600
	solarTime := utcHours + s.lon/15 + eot/60
	hourAngle := (solarTime - 12) * 15 * DEG
	dec := s.solarDeclination(s.utcTime) * DEG
	lat := s.lat
	sinAlt := math.Sin(lat)*math.Sin(dec) + math.Cos(lat)*math.Cos(dec)*math.Cos(hourAngle)
	altitude := math.Asin(sinAlt)
	var azimuth float64
	if altitude > 0.01 {
		cosAz := (math.Sin(dec) - math.Sin(lat)*sinAlt) / (math.Cos(lat)*math.Cos(altitude) + 1e-9)
		azimuth = math.Acos(math.Max(-1, math.Min(1, cosAz)))
		if hourAngle > 0 {
			azimuth = -azimuth + 2*math.Pi
		}
		azimuth = math.Mod(azimuth+2*math.Pi, 2*math.Pi)
	}
	var shadowLen *float64
	if altitude > 0.01 {
		sl := s.height / math.Tan(altitude)
		shadowLen = &sl
	}
	shadowAz := 0.0
	if altitude > 0.01 {
		shadowAz = math.Mod(azimuth+math.Pi, 2*math.Pi) * RAD
	}
	return Result{
		Altitude:     altitude * RAD,
		Azimuth:      azimuth * RAD,
		ShadowLen:    shadowLen,
		ShadowAz:     shadowAz,
		SolarTime:    solarTime,
		Dec:          dec * RAD,
		HourAngle:    hourAngle * RAD,
		AboveHorizon: altitude > 0,
	}
}

func (s *Sundial) printResult(res Result, showShadow bool) {
	fmt.Println("\033[36m☀️ Расчёт солнечной тени:\033[0m")
	fmt.Printf("  \033[33mДата/время:\033[0m %s\n", s.date.Format("2006-01-02 15:04:05"))
	fmt.Printf("  \033[33mШирота:\033[0m %.4f°, Долгота: %.4f°\n", s.lat/RAD, s.lon)
	fmt.Printf("  \033[33mВысота гномона:\033[0m %.2f м\n", s.height)
	if res.AboveHorizon {
		fmt.Printf("  \033[32mВысота Солнца:\033[0m %.2f°\n", res.Altitude)
		fmt.Printf("  \033[32mАзимут Солнца:\033[0m %.2f°\n", res.Azimuth)
		if res.ShadowLen != nil {
			fmt.Printf("  \033[34mДлина тени:\033[0m %.2f м\n", *res.ShadowLen)
		} else {
			fmt.Println("  \033[33mТень бесконечна (Солнце в зените)\033[0m")
		}
		fmt.Printf("  \033[34mНаправление тени:\033[0m %.2f°\n", res.ShadowAz)
		if showShadow {
			s.drawShadow(res)
		}
	} else {
		fmt.Println("\033[31m  Солнце под горизонтом (ночь)\033[0m")
	}
}

func (s *Sundial) drawShadow(res Result) {
	size := 15
	center := size / 2
	scale := 2.0
	var shadowLen float64
	if res.ShadowLen != nil {
		shadowLen = math.Min(*res.ShadowLen, 10) / scale
	}
	shadowAz := res.ShadowAz * DEG
	x := int(float64(center) + shadowLen*math.Sin(shadowAz))
	y := int(float64(center) - shadowLen*math.Cos(shadowAz))
	grid := make([][]string, size)
	for i := range grid {
		grid[i] = make([]string, size)
		for j := range grid[i] {
			grid[i][j] = "░"
		}
	}
	grid[center][center] = "╋"
	if x >= 0 && x < size && y >= 0 && y < size && res.ShadowLen != nil {
		steps := max(abs(x-center), abs(y-center))
		for i := 0; i <= steps; i++ {
			cx := int(float64(center) + float64(x-center)*float64(i)/float64(steps))
			cy := int(float64(center) + float64(y-center)*float64(i)/float64(steps))
			if cx >= 0 && cx < size && cy >= 0 && cy < size && grid[cy][cx] == "░" {
				grid[cy][cx] = "█"
			}
		}
	}
	fmt.Println("\n\033[36mВид сверху (N ↑):\033[0m")
	fmt.Print("  ")
	for i := 0; i < size; i++ {
		fmt.Printf("%2d", i)
	}
	fmt.Println()
	for i := 0; i < size; i++ {
		fmt.Printf("%2d ", i)
		for j := 0; j < size; j++ {
			c := grid[i][j]
			if c == "█" {
				fmt.Print("\033[32m█\033[0m")
			} else if c == "╋" {
				fmt.Print("\033[37m╋\033[0m")
			} else {
				fmt.Print("\033[34m░\033[0m")
			}
		}
		fmt.Println()
	}
}

func (s *Sundial) exportJSON(res Result, filename string) {
	data := map[string]interface{}{
		"time":   s.date.Format(time.RFC3339),
		"lat":    s.lat / RAD,
		"lon":    s.lon,
		"height": s.height,
		"result": res,
	}
	jsonData, _ := json.MarshalIndent(data, "", "  ")
	os.WriteFile(filename, jsonData, 0644)
}

func (s *Sundial) exportCSV(res Result, filename string) {
	f, _ := os.Create(filename)
	defer f.Close()
	w := csv.NewWriter(f)
	defer w.Flush()
	w.Write([]string{"time", "altitude", "azimuth", "shadow_len", "shadow_az"})
	shadowLen := ""
	if res.ShadowLen != nil {
		shadowLen = strconv.FormatFloat(*res.ShadowLen, 'f', 2, 64)
	}
	w.Write([]string{s.date.Format(time.RFC3339), strconv.FormatFloat(res.Altitude, 'f', 2, 64),
		strconv.FormatFloat(res.Azimuth, 'f', 2, 64), shadowLen, strconv.FormatFloat(res.ShadowAz, 'f', 2, 64)})
}

func (s *Sundial) daySeries(stepHours int) []map[string]interface{} {
	var results []map[string]interface{}
	dt := time.Date(s.date.Year(), s.date.Month(), s.date.Day(), 0, 0, 0, 0, time.Local)
	for dt.Hour() < 24 {
		s.utcTime = dt.Add(-time.Duration(s.tzOffset * float64(time.Hour)))
		res := s.Calculate()
		results = append(results, map[string]interface{}{
			"time":         dt.Format(time.RFC3339),
			"altitude":     res.Altitude,
			"azimuth":      res.Azimuth,
			"shadow_len":   res.ShadowLen,
			"shadow_az":    res.ShadowAz,
			"above_horizon": res.AboveHorizon,
		})
		dt = dt.Add(time.Duration(stepHours) * time.Hour)
	}
	return results
}

func main() {
	var (
		lat      float64
		lon      float64
		dateStr  string
		timeStr  string
		height   float64
		tz       float64
		shadow   bool
		day      bool
		jsonFile string
		csvFile  string
	)
	flag.Float64Var(&lat, "lat", 0, "Широта (градусы)")
	flag.Float64Var(&lon, "lon", 0, "Долгота (градусы)")
	flag.StringVar(&dateStr, "date", "", "Дата (YYYY-MM-DD)")
	flag.StringVar(&timeStr, "time", "", "Время (HH:MM)")
	flag.Float64Var(&height, "height", 1.0, "Высота гномона (м)")
	flag.Float64Var(&tz, "tz", 0, "Часовой пояс (часы от UTC)")
	flag.BoolVar(&shadow, "shadow", false, "Показать ASCII-визуализацию тени")
	flag.BoolVar(&day, "day", false, "Расчёт на весь день (почасово)")
	flag.StringVar(&jsonFile, "json", "", "Экспорт в JSON")
	flag.StringVar(&csvFile, "csv", "", "Экспорт в CSV")
	flag.Parse()

	if lat == 0 && lon == 0 {
		fmt.Println("Ошибка: --lat и --lon обязательны")
		os.Exit(1)
	}

	var tzPtr *float64
	if tz != 0 {
		tzPtr = &tz
	}
	sundial := NewSundial(lat, lon, dateStr, timeStr, height, tzPtr)

	if day {
		results := sundial.daySeries(1)
		fmt.Printf("\033[36m📊 Тень на %s (почасово):\033[0m\n", sundial.date.Format("2006-01-02"))
		for _, r := range results {
			if r["above_horizon"].(bool) {
				shadowLen := "∞"
				if r["shadow_len"] != nil {
					shadowLen = strconv.FormatFloat(*(r["shadow_len"].(*float64)), 'f', 2, 64)
				}
				fmt.Printf("  %s  Alt: %5.1f°  Тень: %6s м  Направление: %6.1f°\n",
					r["time"].(string)[11:16], r["altitude"].(float64), shadowLen, r["shadow_az"].(float64))
			} else {
				fmt.Printf("  %s  Ночь\n", r["time"].(string)[11:16])
			}
		}
		if jsonFile != "" {
			data, _ := json.MarshalIndent(results, "", "  ")
			os.WriteFile(jsonFile, data, 0644)
			fmt.Printf("Экспортировано в %s\n", jsonFile)
		}
		if csvFile != "" {
			f, _ := os.Create(csvFile)
			defer f.Close()
			w := csv.NewWriter(f)
			defer w.Flush()
			w.Write([]string{"time", "altitude", "azimuth", "shadow_len", "shadow_az"})
			for _, r := range results {
				shadowLen := ""
				if r["shadow_len"] != nil {
					shadowLen = strconv.FormatFloat(*(r["shadow_len"].(*float64)), 'f', 2, 64)
				}
				w.Write([]string{r["time"].(string), strconv.FormatFloat(r["altitude"].(float64), 'f', 2, 64),
					strconv.FormatFloat(r["azimuth"].(float64), 'f', 2, 64), shadowLen,
					strconv.FormatFloat(r["shadow_az"].(float64), 'f', 2, 64)})
			}
			fmt.Printf("Экспортировано в %s\n", csvFile)
		}
	} else {
		res := sundial.Calculate()
		sundial.printResult(res, shadow)
		if jsonFile != "" {
			sundial.exportJSON(res, jsonFile)
			fmt.Printf("\033[32mЭкспортировано в %s\033[0m\n", jsonFile)
		}
		if csvFile != "" {
			sundial.exportCSV(res, csvFile)
			fmt.Printf("\033[32mЭкспортировано в %s\033[0m\n", csvFile)
		}
	}
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
