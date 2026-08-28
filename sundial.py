

```python
#!/usr/bin/env python3
# sundial.py
import argparse
import math
import json
import csv
import sys
import time
from datetime import datetime, timedelta, timezone
from colorama import init, Fore, Style

init(autoreset=True)

# Константы
DEG = math.pi / 180
RAD = 180 / math.pi

class Sundial:
    def __init__(self, lat, lon, date=None, time_str=None, height=1.0, tz_offset=None):
        self.lat = lat * DEG
        self.lon = lon
        self.height = height
        self.tz_offset = tz_offset if tz_offset is not None else self._get_tz_offset()
        if date:
            self.date = datetime.strptime(date, "%Y-%m-%d")
        else:
            self.date = datetime.now()
        if time_str:
            parts = time_str.split(':')
            h = int(parts[0])
            m = int(parts[1]) if len(parts) > 1 else 0
            s = int(parts[2]) if len(parts) > 2 else 0
            self.time = self.date.replace(hour=h, minute=m, second=s)
        else:
            self.time = datetime.now()
        self.time = self.time.replace(tzinfo=timezone.utc) if self.time.tzinfo is None else self.time
        # Приводим к UTC
        self.utc_time = self.time - timedelta(hours=self.tz_offset)

    def _get_tz_offset(self):
        # Простое определение: локальное смещение
        return -time.timezone / 3600 if time.timezone else 0

    def _julian_day(self, dt):
        # Упрощённый расчёт юлианского дня
        y = dt.year
        m = dt.month
        d = dt.day + dt.hour/24 + dt.minute/1440 + dt.second/86400
        if m <= 2:
            y -= 1
            m += 12
        A = y // 100
        B = 2 - A + A // 4
        return int(365.25 * (y + 4716)) + int(30.6001 * (m + 1)) + d + B - 1524.5

    def _solar_declination(self, dt):
        # Склонение Солнца (градусы)
        n = (dt - datetime(dt.year, 1, 1)).days + 1
        # Приблизительная формула
        dec = -23.44 * math.cos(DEG * (360/365) * (n + 10))
        return dec

    def _equation_of_time(self, dt):
        # Уравнение времени (минуты)
        n = (dt - datetime(dt.year, 1, 1)).days + 1
        B = (360/365) * (n - 81)
        B_rad = B * DEG
        eot = 9.87 * math.sin(2 * B_rad) - 7.53 * math.cos(B_rad) - 1.5 * math.sin(B_rad)
        return eot

    def calculate(self):
        # Юлианский день
        jd = self._julian_day(self.utc_time)
        # Солнечное время (истинное)
        eot = self._equation_of_time(self.utc_time)
        solar_time = (self.utc_time.hour * 60 + self.utc_time.minute + self.utc_time.second/60 + self.lon/15*60 + eot) / 60
        # Часовой угол
        hour_angle = (solar_time - 12) * 15 * DEG
        # Склонение
        dec = self._solar_declination(self.utc_time) * DEG
        # Высота Солнца
        lat = self.lat
        sin_alt = math.sin(lat) * math.sin(dec) + math.cos(lat) * math.cos(dec) * math.cos(hour_angle)
        altitude = math.asin(sin_alt)
        # Азимут Солнца (от севера по часовой)
        cos_az = (math.sin(dec) - math.sin(lat) * sin_alt) / (math.cos(lat) * math.cos(altitude) + 1e-9)
        azimuth = math.acos(max(-1, min(1, cos_az)))
        if hour_angle > 0:
            azimuth = -azimuth + 2 * math.pi
        azimuth = (azimuth + 2 * math.pi) % (2 * math.pi)
        # Длина тени
        if altitude > 0.01:
            shadow_len = self.height / math.tan(altitude)
        else:
            shadow_len = float('inf')
        # Направление тени (противоположное азимуту Солнца)
        shadow_az = (azimuth + math.pi) % (2 * math.pi)
        return {
            "altitude": altitude * RAD,
            "azimuth": azimuth * RAD,
            "shadow_len": shadow_len if shadow_len != float('inf') else None,
            "shadow_az": shadow_az * RAD,
            "solar_time": solar_time,
            "dec": dec * RAD,
            "hour_angle": hour_angle * RAD,
            "above_horizon": altitude > 0
        }

    def print_result(self, res, show_shadow=False):
        print(Fore.CYAN + "☀️ Расчёт солнечной тени:")
        print(f"  {Fore.YELLOW}Дата/время:{Fore.RESET} {self.time.strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"  {Fore.YELLOW}Широта:{Fore.RESET} {self.lat/RAD:.4f}°, Долгота: {self.lon:.4f}°")
        print(f"  {Fore.YELLOW}Высота гномона:{Fore.RESET} {self.height:.2f} м")
        if res["above_horizon"]:
            print(f"  {Fore.GREEN}Высота Солнца:{Fore.RESET} {res['altitude']:.2f}°")
            print(f"  {Fore.GREEN}Азимут Солнца:{Fore.RESET} {res['azimuth']:.2f}°")
            if res["shadow_len"] is not None:
                print(f"  {Fore.BLUE}Длина тени:{Fore.RESET} {res['shadow_len']:.2f} м")
            else:
                print(f"  {Fore.YELLOW}Тень бесконечна (Солнце в зените){Fore.RESET}")
            print(f"  {Fore.BLUE}Направление тени:{Fore.RESET} {res['shadow_az']:.2f}°")
            if show_shadow:
                self._draw_shadow(res)
        else:
            print(Fore.RED + "  Солнце под горизонтом (ночь)" + Fore.RESET)

    def _draw_shadow(self, res):
        # ASCII-визуализация тени (вид сверху)
        size = 15
        center = size // 2
        scale = 2.0
        shadow_len = min(res["shadow_len"], 10.0) if res["shadow_len"] else 0
        shadow_len = shadow_len / scale
        shadow_az = res["shadow_az"] * DEG
        # Вычисляем координаты конца тени
        x = int(center + shadow_len * math.sin(shadow_az))
        y = int(center - shadow_len * math.cos(shadow_az))
        # Рисуем сетку
        grid = [['░' for _ in range(size)] for _ in range(size)]
        # Центр (гномон)
        grid[center][center] = '╋'
        # Тень
        if 0 <= x < size and 0 <= y < size:
            # Линия от центра к концу тени (простая аппроксимация)
            steps = max(abs(x-center), abs(y-center))
            for i in range(steps+1):
                cx = int(center + (x-center) * i / steps)
                cy = int(center + (y-center) * i / steps)
                if 0 <= cx < size and 0 <= cy < size and grid[cy][cx] == '░':
                    grid[cy][cx] = '█'
        # Вывод
        print(Fore.CYAN + "\nВид сверху (N ↑):")
        print("  " + "".join(f"{i:2}" for i in range(size)))
        for i, row in enumerate(grid):
            print(f"{i:2} " + "".join(Fore.GREEN if c == '█' else Fore.WHITE if c == '╋' else Fore.BLUE for c in row))

    def export_json(self, res, filename):
        with open(filename, 'w') as f:
            json.dump({
                "time": self.time.isoformat(),
                "lat": self.lat/RAD,
                "lon": self.lon,
                "height": self.height,
                "result": res
            }, f, indent=2)

    def export_csv(self, res, filename):
        with open(filename, 'w', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=["time", "altitude", "azimuth", "shadow_len", "shadow_az"])
            writer.writeheader()
            writer.writerow({
                "time": self.time.isoformat(),
                "altitude": res["altitude"],
                "azimuth": res["azimuth"],
                "shadow_len": res["shadow_len"],
                "shadow_az": res["shadow_az"]
            })

    def day_series(self, step_hours=1):
        # Расчёт тени на весь день
        results = []
        dt = self.date.replace(hour=0, minute=0, second=0)
        while dt <= self.date.replace(hour=23, minute=59, second=59):
            self.time = dt
            self.utc_time = self.time - timedelta(hours=self.tz_offset)
            res = self.calculate()
            res["time"] = dt.isoformat()
            results.append(res)
            dt += timedelta(hours=step_hours)
        return results

def main():
    parser = argparse.ArgumentParser(description="Солнечные часы (тень)")
    parser.add_argument("--lat", type=float, required=True, help="Широта (градусы)")
    parser.add_argument("--lon", type=float, required=True, help="Долгота (градусы)")
    parser.add_argument("--date", help="Дата (YYYY-MM-DD)")
    parser.add_argument("--time", help="Время (HH:MM или HH:MM:SS)")
    parser.add_argument("--height", type=float, default=1.0, help="Высота гномона (м)")
    parser.add_argument("--tz", type=float, help="Часовой пояс (часы от UTC)")
    parser.add_argument("--shadow", action="store_true", help="Показать ASCII-визуализацию тени")
    parser.add_argument("--day", action="store_true", help="Расчёт на весь день (почасово)")
    parser.add_argument("--json", help="Экспорт в JSON")
    parser.add_argument("--csv", help="Экспорт в CSV")
    args = parser.parse_args()

    sundial = Sundial(args.lat, args.lon, args.date, args.time, args.height, args.tz)

    if args.day:
        results = sundial.day_series()
        print(Fore.CYAN + f"📊 Тень на {sundial.date.strftime('%Y-%m-%d')} (почасово):")
        for r in results:
            if r["above_horizon"]:
                print(f"  {r['time'][11:16]}  Alt: {r['altitude']:5.1f}°  Тень: {r['shadow_len'] if r['shadow_len'] is not None else '∞':>6} м  Направление: {r['shadow_az']:6.1f}°")
            else:
                print(f"  {r['time'][11:16]}  Ночь (Солнце под горизонтом)")
        if args.json:
            with open(args.json, 'w') as f:
                json.dump(results, f, indent=2)
            print(f"Экспортировано в {args.json}")
        if args.csv:
            with open(args.csv, 'w', newline='') as f:
                writer = csv.DictWriter(f, fieldnames=["time", "altitude", "azimuth", "shadow_len", "shadow_az"])
                writer.writeheader()
                for r in results:
                    writer.writerow(r)
            print(f"Экспортировано в {args.csv}")
    else:
        res = sundial.calculate()
        sundial.print_result(res, args.shadow)
        if args.json:
            sundial.export_json(res, args.json)
            print(Fore.GREEN + f"Экспортировано в {args.json}")
        if args.csv:
            sundial.export_csv(res, args.csv)
            print(Fore.GREEN + f"Экспортировано в {args.csv}")

if __name__ == "__main__":
    main()
