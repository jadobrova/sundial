// Sundial.cs
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Sundial
{
    class Program
    {
        static void Main(string[] args)
        {
            var opts = ParseArgs(args);
            var sundial = new Sundial(opts);
            if (opts.Day)
            {
                var results = sundial.DaySeries();
                Console.WriteLine($"\u001B[36m📊 Тень на {sundial.DateTime:yyyy-MM-dd} (почасово):\u001B[0m");
                foreach (var r in results)
                {
                    if ((bool)r["above_horizon"])
                    {
                        string shadowLen = r["shadow_len"] != null ? $"{r["shadow_len"]:F2}" : "∞";
                        Console.WriteLine($"  {r["time"]:HH:mm}  Alt: {r["altitude"]:F1}°  Тень: {shadowLen,6} м  Направление: {r["shadow_az"]:F1}°");
                    }
                    else
                    {
                        Console.WriteLine($"  {r["time"]:HH:mm}  Ночь");
                    }
                }
                if (opts.Json != null)
                {
                    string json = JsonSerializer.Serialize(results, new JsonSerializerOptions { WriteIndented = true });
                    File.WriteAllText(opts.Json, json);
                    Console.WriteLine($"Экспортировано в {opts.Json}");
                }
                if (opts.Csv != null)
                {
                    using var sw = new StreamWriter(opts.Csv);
                    sw.WriteLine("time,altitude,azimuth,shadow_len,shadow_az");
                    foreach (var r in results)
                    {
                        sw.WriteLine($"{r["time"]},{r["altitude"]},{r["azimuth"]},{r["shadow_len"]},{r["shadow_az"]}");
                    }
                    Console.WriteLine($"Экспортировано в {opts.Csv}");
                }
            }
            else
            {
                var res = sundial.Calculate();
                sundial.PrintResult(res);
                if (opts.Json != null)
                {
                    sundial.ExportJson(res, opts.Json);
                    Console.WriteLine($"\u001B[32mЭкспортировано в {opts.Json}\u001B[0m");
                }
                if (opts.Csv != null)
                {
                    sundial.ExportCsv(res, opts.Csv);
                    Console.WriteLine($"\u001B[32mЭкспортировано в {opts.Csv}\u001B[0m");
                }
            }
        }

        static Options ParseArgs(string[] args)
        {
            var opts = new Options();
            for (int i = 0; i < args.Length; i++)
            {
                switch (args[i])
                {
                    case "--lat": opts.Lat = double.Parse(args[++i], CultureInfo.InvariantCulture); break;
                    case "--lon": opts.Lon = double.Parse(args[++i], CultureInfo.InvariantCulture); break;
                    case "--date": opts.Date = args[++i]; break;
                    case "--time": opts.Time = args[++i]; break;
                    case "--height": opts.Height = double.Parse(args[++i], CultureInfo.InvariantCulture); break;
                    case "--tz": opts.Tz = double.Parse(args[++i], CultureInfo.InvariantCulture); break;
                    case "--shadow": opts.Shadow = true; break;
                    case "--day": opts.Day = true; break;
                    case "--json": opts.Json = args[++i]; break;
                    case "--csv": opts.Csv = args[++i]; break;
                }
            }
            return opts;
        }

        class Options
        {
            public double Lat { get; set; }
            public double Lon { get; set; }
            public string Date { get; set; }
            public string Time { get; set; }
            public double Height { get; set; } = 1.0;
            public double? Tz { get; set; }
            public bool Shadow { get; set; }
            public bool Day { get; set; }
            public string Json { get; set; }
            public string Csv { get; set; }
        }

        class Result
        {
            public double Altitude { get; set; }
            public double Azimuth { get; set; }
            public double? ShadowLen { get; set; }
            public double ShadowAz { get; set; }
            public double SolarTime { get; set; }
            public double Dec { get; set; }
            public double HourAngle { get; set; }
            public bool AboveHorizon { get; set; }
        }

        class Sundial
        {
            private const double DEG = Math.PI / 180;
            private const double RAD = 180 / Math.PI;

            private readonly double lat, lon, height, tzOffset;
            public DateTime DateTime { get; private set; }
            private DateTime utcTime;

            public Sundial(Options opts)
            {
                lat = opts.Lat * DEG;
                lon = opts.Lon;
                height = opts.Height;
                tzOffset = opts.Tz ?? -(double)TimeZoneInfo.Local.GetUtcOffset(DateTime.UtcNow).TotalHours;
                DateTime dt = DateTime.UtcNow;
                if (opts.Date != null)
                    dt = DateTime.ParseExact(opts.Date, "yyyy-MM-dd", CultureInfo.InvariantCulture);
                if (opts.Time != null)
                {
                    var parts = opts.Time.Split(':');
                    dt = dt.Date.AddHours(int.Parse(parts[0])).AddMinutes(int.Parse(parts[1]));
                    if (parts.Length > 2) dt = dt.AddSeconds(int.Parse(parts[2]));
                }
                DateTime = dt;
                utcTime = dt.AddHours(-tzOffset);
            }

            private double JulianDay(DateTime dt)
            {
                int y = dt.Year;
                int m = dt.Month;
                double d = dt.Day + dt.Hour/24.0 + dt.Minute/1440.0 + dt.Second/86400.0;
                if (m <= 2) { y--; m += 12; }
                int A = y / 100;
                int B = 2 - A + A / 4;
                return Math.Floor(365.25 * (y + 4716)) + Math.Floor(30.6001 * (m + 1)) + d + B - 1524.5;
            }

            private double SolarDeclination(DateTime dt)
            {
                var start = new DateTime(dt.Year, 1, 1, 0, 0, 0, DateTimeKind.Utc);
                double n = (dt - start).TotalDays + 1;
                return -23.44 * Math.Cos(DEG * (360.0/365.0) * (n + 10));
            }

            private double EquationOfTime(DateTime dt)
            {
                var start = new DateTime(dt.Year, 1, 1, 0, 0, 0, DateTimeKind.Utc);
                double n = (dt - start).TotalDays + 1;
                double B = (360.0 / 365.0) * (n - 81);
                double B_rad = B * DEG;
                return 9.87 * Math.Sin(2 * B_rad) - 7.53 * Math.Cos(B_rad) - 1.5 * Math.Sin(B_rad);
            }

            public Result Calculate()
            {
                double eot = EquationOfTime(utcTime);
                double utcHours = utcTime.Hour + utcTime.Minute/60.0 + utcTime.Second/3600.0;
                double solarTime = utcHours + lon/15.0 + eot/60.0;
                double hourAngle = (solarTime - 12) * 15 * DEG;
                double dec = SolarDeclination(utcTime) * DEG;
                double latRad = lat;
                double sinAlt = Math.Sin(latRad) * Math.Sin(dec) + Math.Cos(latRad) * Math.Cos(dec) * Math.Cos(hourAngle);
                double altitude = Math.Asin(sinAlt);
                double azimuth = 0;
                if (altitude > 0.01)
                {
                    double cosAz = (Math.Sin(dec) - Math.Sin(latRad) * sinAlt) / (Math.Cos(latRad) * Math.Cos(altitude) + 1e-9);
                    azimuth = Math.Acos(Math.Max(-1, Math.Min(1, cosAz)));
                    if (hourAngle > 0) azimuth = -azimuth + 2*Math.PI;
                    azimuth = (azimuth + 2*Math.PI) % (2*Math.PI);
                }
                double? shadowLen = altitude > 0.01 ? height / Math.Tan(altitude) : (double?)null;
                double shadowAz = altitude > 0.01 ? (azimuth + Math.PI) % (2*Math.PI) * RAD : 0;
                return new Result
                {
                    Altitude = altitude * RAD,
                    Azimuth = azimuth * RAD,
                    ShadowLen = shadowLen,
                    ShadowAz = shadowAz,
                    SolarTime = solarTime,
                    Dec = dec * RAD,
                    HourAngle = hourAngle * RAD,
                    AboveHorizon = altitude > 0
                };
            }

            public void PrintResult(Result res)
            {
                bool color = !Console.IsOutputRedirected;
                string c = color ? "\u001B[36m" : "", cY = color ? "\u001B[33m" : "", cG = color ? "\u001B[32m" : "", cB = color ? "\u001B[34m" : "", cR = color ? "\u001B[31m" : "", reset = color ? "\u001B[0m" : "";
                Console.WriteLine($"{c}☀️ Расчёт солнечной тени:{reset}");
                Console.WriteLine($"  {cY}Дата/время:{reset} {DateTime:yyyy-MM-dd HH:mm:ss}");
                Console.WriteLine($"  {cY}Широта:{reset} {lat/RAD:F4}°, Долгота: {lon:F4}°");
                Console.WriteLine($"  {cY}Высота гномона:{reset} {height:F2} м");
                if (res.AboveHorizon)
                {
                    Console.WriteLine($"  {cG}Высота Солнца:{reset} {res.Altitude:F2}°");
                    Console.WriteLine($"  {cG}Азимут Солнца:{reset} {res.Azimuth:F2}°");
                    if (res.ShadowLen.HasValue)
                        Console.WriteLine($"  {cB}Длина тени:{reset} {res.ShadowLen.Value:F2} м");
                    else
                        Console.WriteLine($"  {c}Тень бесконечна (Солнце в зените){reset}");
                    Console.WriteLine($"  {cB}Направление тени:{reset} {res.ShadowAz:F2}°");
                    if (opts.Shadow) DrawShadow(res);
                }
                else
                {
                    Console.WriteLine($"{cR}  Солнце под горизонтом (ночь){reset}");
                }
            }

            private void DrawShadow(Result res)
            {
                int size = 15, center = size / 2;
                double scale = 2.0;
                double shadowLen = res.ShadowLen.HasValue ? Math.Min(res.ShadowLen.Value, 10) / scale : 0;
                double shadowAz = res.ShadowAz * DEG;
                int x = (int)Math.Round(center + shadowLen * Math.Sin(shadowAz));
                int y = (int)Math.Round(center - shadowLen * Math.Cos(shadowAz));
                char[][] grid = new char[size][];
                for (int i = 0; i < size; i++) { grid[i] = new char[size]; for (int j = 0; j < size; j++) grid[i][j] = '░'; }
                grid[center][center] = '╋';
                if (x >= 0 && x < size && y >= 0 && y < size && res.ShadowLen.HasValue)
                {
                    int steps = Math.Max(Math.Abs(x-center), Math.Abs(y-center));
                    for (int i = 0; i <= steps; i++)
                    {
                        int cx = (int)Math.Round(center + (double)(x-center) * i / steps);
                        int cy = (int)Math.Round(center + (double)(y-center) * i / steps);
                        if (cx >= 0 && cx < size && cy >= 0 && cy < size && grid[cy][cx] == '░')
                            grid[cy][cx] = '█';
                    }
                }
                Console.WriteLine("\n\u001B[36mВид сверху (N ↑):\u001B[0m");
                Console.Write("  ");
                for (int i = 0; i < size; i++) Console.Write($"{i,2}");
                Console.WriteLine();
                for (int i = 0; i < size; i++)
                {
                    Console.Write($"{i,2} ");
                    for (int j = 0; j < size; j++)
                    {
                        char c = grid[i][j];
                        Console.Write(c == '█' ? "\u001B[32m█\u001B[0m" : c == '╋' ? "\u001B[37m╋\u001B[0m" : "\u001B[34m░\u001B[0m");
                    }
                    Console.WriteLine();
                }
            }

            public void ExportJson(Result res, string filename)
            {
                var data = new { time = DateTime, lat = lat/RAD, lon, height, result = res };
                string json = JsonSerializer.Serialize(data, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(filename, json);
            }

            public void ExportCsv(Result res, string filename)
            {
                using var sw = new StreamWriter(filename);
                sw.WriteLine("time,altitude,azimuth,shadow_len,shadow_az");
                sw.WriteLine($"{DateTime:o},{res.Altitude:F2},{res.Azimuth:F2},{res.ShadowLen},{res.ShadowAz:F2}");
            }

            public List<Dictionary<string, object>> DaySeries()
            {
                var results = new List<Dictionary<string, object>>();
                var dt = DateTime.Date;
                while (dt.Hour < 24)
                {
                    var tmp = new Sundial(new Options { Lat = lat/RAD, Lon = lon, Height = height, Tz = tzOffset });
                    tmp.DateTime = dt;
                    tmp.utcTime = dt.AddHours(-tzOffset);
                    var res = tmp.Calculate();
                    results.Add(new Dictionary<string, object>
                    {
                        ["time"] = dt,
                        ["altitude"] = res.Altitude,
                        ["azimuth"] = res.Azimuth,
                        ["shadow_len"] = res.ShadowLen,
                        ["shadow_az"] = res.ShadowAz,
                        ["above_horizon"] = res.AboveHorizon
                    });
                    dt = dt.AddHours(1);
                }
                return results;
            }
        }
    }
}
