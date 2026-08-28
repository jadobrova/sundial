// sundial.rs
use chrono::{DateTime, Duration, Local, NaiveDate, NaiveDateTime, TimeZone, Utc};
use clap::{App, Arg};
use serde::{Deserialize, Serialize};
use serde_json;
use std::fs;
use std::io::Write;
use colored::*;

const DEG: f64 = std::f64::consts::PI / 180.0;
const RAD: f64 = 180.0 / std::f64::consts::PI;

#[derive(Serialize, Deserialize)]
struct Result {
    altitude: f64,
    azimuth: f64,
    shadow_len: Option<f64>,
    shadow_az: f64,
    solar_time: f64,
    dec: f64,
    hour_angle: f64,
    above_horizon: bool,
}

struct Sundial {
    lat: f64,
    lon: f64,
    height: f64,
    tz_offset: f64,
    date: DateTime<Local>,
    utc_time: DateTime<Utc>,
}

impl Sundial {
    fn new(lat: f64, lon: f64, date_str: Option<&str>, time_str: Option<&str>, height: f64, tz: Option<f64>) -> Self {
        let tz_offset = tz.unwrap_or_else(|| {
            let local = Local::now();
            local.offset().local_minus_utc() as f64 / 3600.0
        });
        let mut date = Local::now();
        if let Some(ds) = date_str {
            if let Ok(nd) = NaiveDate::parse_from_str(ds, "%Y-%m-%d") {
                date = Local.from_local_datetime(&nd.and_hms_opt(0,0,0).unwrap()).unwrap();
            }
        }
        if let Some(ts) = time_str {
            let parts: Vec<&str> = ts.split(':').collect();
            if parts.len() >= 2 {
                let h = parts[0].parse::<u32>().unwrap_or(0);
                let m = parts[1].parse::<u32>().unwrap_or(0);
                let s = if parts.len() >= 3 { parts[2].parse::<u32>().unwrap_or(0) } else { 0 };
                date = Local.from_local_datetime(&NaiveDateTime::new(date.naive_local().date(), h, m, s)).unwrap();
            }
        }
        let utc_time = date.with_timezone(&Utc);
        Sundial { lat: lat * DEG, lon, height, tz_offset, date, utc_time }
    }

    fn julian_day(&self, dt: &DateTime<Utc>) -> f64 {
        let y = dt.year();
        let m = dt.month() as i32;
        let d = dt.day() as f64 + dt.hour() as f64/24.0 + dt.minute() as f64/1440.0 + dt.second() as f64/86400.0;
        let (y2, m2) = if m <= 2 { (y-1, m+12) } else { (y, m) };
        let a = y2 / 100;
        let b = 2 - a + a / 4;
        (365.25 * (y2 + 4716) as f64).floor() + (30.6001 * (m2 + 1) as f64).floor() + d + b as f64 - 1524.5
    }

    fn solar_declination(&self, dt: &DateTime<Utc>) -> f64 {
        let start = Utc.with_ymd_and_hms(dt.year(), 1, 1, 0, 0, 0).unwrap();
        let n = (dt - start).num_days() as f64 + 1.0;
        -23.44 * (DEG * (360.0/365.0) * (n + 10.0)).cos()
    }

    fn equation_of_time(&self, dt: &DateTime<Utc>) -> f64 {
        let start = Utc.with_ymd_and_hms(dt.year(), 1, 1, 0, 0, 0).unwrap();
        let n = (dt - start).num_days() as f64 + 1.0;
        let b = (360.0 / 365.0) * (n - 81.0);
        let b_rad = b * DEG;
        9.87 * (2.0 * b_rad).sin() - 7.53 * b_rad.cos() - 1.5 * b_rad.sin()
    }

    fn calculate(&self) -> Result {
        let eot = self.equation_of_time(&self.utc_time);
        let utc_hours = self.utc_time.hour() as f64 + self.utc_time.minute() as f64 / 60.0 + self.utc_time.second() as f64 / 3600.0;
        let solar_time = utc_hours + self.lon / 15.0 + eot / 60.0;
        let hour_angle = (solar_time - 12.0) * 15.0 * DEG;
        let dec = self.solar_declination(&self.utc_time) * DEG;
        let lat = self.lat;
        let sin_alt = lat.sin() * dec.sin() + lat.cos() * dec.cos() * hour_angle.cos();
        let altitude = sin_alt.asin();
        let mut azimuth = 0.0;
        if altitude > 0.01 {
            let cos_az = (dec.sin() - lat.sin() * sin_alt) / (lat.cos() * altitude.cos() + 1e-9);
            azimuth = cos_az.max(-1.0).min(1.0).acos();
            if hour_angle > 0.0 {
                azimuth = -azimuth + 2.0 * std::f64::consts::PI;
            }
            azimuth = (azimuth + 2.0 * std::f64::consts::PI) % (2.0 * std::f64::consts::PI);
        }
        let shadow_len = if altitude > 0.01 { Some(self.height / altitude.tan()) } else { None };
        let shadow_az = if altitude > 0.01 { (azimuth + std::f64::consts::PI) % (2.0 * std::f64::consts::PI) * RAD } else { 0.0 };
        Result {
            altitude: altitude * RAD,
            azimuth: azimuth * RAD,
            shadow_len,
            shadow_az,
            solar_time,
            dec: dec * RAD,
            hour_angle: hour_angle * RAD,
            above_horizon: altitude > 0.0,
        }
    }

    fn print_result(&self, res: &Result, show_shadow: bool) {
        println!("{}", "☀️ Расчёт солнечной тени:".cyan());
        println!("  {} {}", "Дата/время:".yellow(), self.date.format("%Y-%m-%d %H:%M:%S"));
        println!("  {} {:.4}°, Долгота: {:.4}°", "Широта:".yellow(), self.lat / RAD, self.lon);
        println!("  {} {:.2} м", "Высота гномона:".yellow(), self.height);
        if res.above_horizon {
            println!("  {} {:.2}°", "Высота Солнца:".green(), res.altitude);
            println!("  {} {:.2}°", "Азимут Солнца:".green(), res.azimuth);
            if let Some(sl) = res.shadow_len {
                println!("  {} {:.2} м", "Длина тени:".blue(), sl);
            } else {
                println!("  {}", "Тень бесконечна (Солнце в зените)".yellow());
            }
            println!("  {} {:.2}°", "Направление тени:".blue(), res.shadow_az);
            if show_shadow { self.draw_shadow(res); }
        } else {
            println!("{}", "  Солнце под горизонтом (ночь)".red());
        }
    }

    fn draw_shadow(&self, res: &Result) {
        let size = 15;
        let center = size / 2;
        let scale = 2.0;
        let shadow_len = if let Some(sl) = res.shadow_len { sl.min(10.0) / scale } else { 0.0 };
        let shadow_az = res.shadow_az * DEG;
        let x = (center as f64 + shadow_len * shadow_az.sin()).round() as i32;
        let y = (center as f64 - shadow_len * shadow_az.cos()).round() as i32;
        let mut grid = vec![vec!['░'; size]; size];
        grid[center][center] = '╋';
        if x >= 0 && x < size as i32 && y >= 0 && y < size as i32 && res.shadow_len.is_some() {
            let steps = (x - center as i32).abs().max((y - center as i32).abs());
            for i in 0..=steps {
                let cx = (center as f64 + (x - center as i32) as f64 * i as f64 / steps as f64).round() as i32;
                let cy = (center as f64 + (y - center as i32) as f64 * i as f64 / steps as f64).round() as i32;
                if cx >= 0 && cx < size as i32 && cy >= 0 && cy < size as i32 && grid[cy as usize][cx as usize] == '░' {
                    grid[cy as usize][cx as usize] = '█';
                }
            }
        }
        println!("\n{}", "Вид сверху (N ↑):".cyan());
        print!("  ");
        for i in 0..size { print!("{:2}", i); }
        println!();
        for i in 0..size {
            print!("{:2} ", i);
            for j in 0..size {
                let c = grid[i][j];
                if c == '█' { print!("{}", "█".green()); }
                else if c == '╋' { print!("{}", "╋".white()); }
                else { print!("{}", "░".blue()); }
            }
            println!();
        }
    }

    fn export_json(&self, res: &Result, filename: &str) {
        let data = serde_json::json!({
            "time": self.date.to_rfc3339(),
            "lat": self.lat / RAD,
            "lon": self.lon,
            "height": self.height,
            "result": res
        });
        let json = serde_json::to_string_pretty(&data).unwrap();
        fs::write(filename, json).unwrap();
    }

    fn export_csv(&self, res: &Result, filename: &str) {
        let mut wtr = csv::Writer::from_path(filename).unwrap();
        wtr.write_record(&["time", "altitude", "azimuth", "shadow_len", "shadow_az"]).unwrap();
        let shadow_len = if let Some(sl) = res.shadow_len { sl.to_string() } else { "".to_string() };
        wtr.write_record(&[
            self.date.to_rfc3339(),
            res.altitude.to_string(),
            res.azimuth.to_string(),
            shadow_len,
            res.shadow_az.to_string(),
        ]).unwrap();
        wtr.flush().unwrap();
    }

    fn day_series(&self, step_hours: i64) -> Vec<serde_json::Value> {
        let mut results = Vec::new();
        let mut dt = self.date.with_hour(0).unwrap().with_minute(0).unwrap().with_second(0).unwrap();
        while dt.hour() < 24 {
            let utc = dt.with_timezone(&Utc);
            let tmp = Sundial { lat: self.lat, lon: self.lon, height: self.height, tz_offset: self.tz_offset, date: dt, utc_time: utc };
            let res = tmp.calculate();
            results.push(serde_json::json!({
                "time": dt.to_rfc3339(),
                "altitude": res.altitude,
                "azimuth": res.azimuth,
                "shadow_len": res.shadow_len,
                "shadow_az": res.shadow_az,
                "above_horizon": res.above_horizon
            }));
            dt = dt + Duration::hours(step_hours);
        }
        results
    }
}

fn main() {
    let matches = App::new("Sundial")
        .arg(Arg::with_name("lat").long("lat").takes_value(true).required(true).help("Широта"))
        .arg(Arg::with_name("lon").long("lon").takes_value(true).required(true).help("Долгота"))
        .arg(Arg::with_name("date").long("date").takes_value(true).help("Дата (YYYY-MM-DD)"))
        .arg(Arg::with_name("time").long("time").takes_value(true).help("Время (HH:MM)"))
        .arg(Arg::with_name("height").long("height").takes_value(true).default_value("1.0").help("Высота гномона"))
        .arg(Arg::with_name("tz").long("tz").takes_value(true).help("Часовой пояс"))
        .arg(Arg::with_name("shadow").long("shadow").help("Показать ASCII-визуализацию тени"))
        .arg(Arg::with_name("day").long("day").help("Расчёт на весь день"))
        .arg(Arg::with_name("json").long("json").takes_value(true).help("Экспорт в JSON"))
        .arg(Arg::with_name("csv").long("csv").takes_value(true).help("Экспорт в CSV"))
        .get_matches();

    let lat: f64 = matches.value_of("lat").unwrap().parse().unwrap();
    let lon: f64 = matches.value_of("lon").unwrap().parse().unwrap();
    let date_str = matches.value_of("date");
    let time_str = matches.value_of("time");
    let height: f64 = matches.value_of("height").unwrap().parse().unwrap();
    let tz: Option<f64> = matches.value_of("tz").map(|s| s.parse().unwrap());
    let shadow = matches.is_present("shadow");
    let day = matches.is_present("day");

    let sundial = Sundial::new(lat, lon, date_str, time_str, height, tz);

    if day {
        let results = sundial.day_series(1);
        println!("{}", format!("📊 Тень на {} (почасово):", sundial.date.format("%Y-%m-%d")).cyan());
        for r in &results {
            if r["above_horizon"].as_bool().unwrap_or(false) {
                let shadow_len = if let Some(sl) = r["shadow_len"].as_f64() {
                    format!("{:.2}", sl)
                } else { "∞".to_string() };
                println!("  {}  Alt: {:5.1}°  Тень: {:>6} м  Направление: {:6.1}°",
                    r["time"].as_str().unwrap()[11..16],
                    r["altitude"].as_f64().unwrap(),
                    shadow_len,
                    r["shadow_az"].as_f64().unwrap()
                );
            } else {
                println!("  {}  Ночь", r["time"].as_str().unwrap()[11..16]);
            }
        }
        if let Some(file) = matches.value_of("json") {
            let json = serde_json::to_string_pretty(&results).unwrap();
            fs::write(file, json).unwrap();
            println!("Экспортировано в {}", file);
        }
        if let Some(file) = matches.value_of("csv") {
            let mut wtr = csv::Writer::from_path(file).unwrap();
            wtr.write_record(&["time", "altitude", "azimuth", "shadow_len", "shadow_az"]).unwrap();
            for r in &results {
                let shadow_len = if let Some(sl) = r["shadow_len"].as_f64() { sl.to_string() } else { "".to_string() };
                wtr.write_record(&[
                    r["time"].as_str().unwrap(),
                    r["altitude"].as_f64().unwrap().to_string(),
                    r["azimuth"].as_f64().unwrap().to_string(),
                    shadow_len,
                    r["shadow_az"].as_f64().unwrap().to_string(),
                ]).unwrap();
            }
            wtr.flush().unwrap();
            println!("Экспортировано в {}", file);
        }
    } else {
        let res = sundial.calculate();
        sundial.print_result(&res, shadow);
        if let Some(file) = matches.value_of("json") {
            sundial.export_json(&res, file);
            println!("{}", format!("Экспортировано в {}", file).green());
        }
        if let Some(file) = matches.value_of("csv") {
            sundial.export_csv(&res, file);
            println!("{}", format!("Экспортировано в {}", file).green());
        }
    }
}
