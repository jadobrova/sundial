#!/usr/bin/env node
//sundial.js 
const { program } = require('commander');
const chalk = require('chalk');
const fs = require('fs');

const DEG = Math.PI / 180;
const RAD = 180 / Math.PI;

class Sundial {
    constructor(lat, lon, dateStr, timeStr, height, tzOffset) {
        this.lat = lat * DEG;
        this.lon = lon;
        this.height = height || 1.0;
        this.tzOffset = tzOffset !== undefined ? tzOffset : this._getTzOffset();
        if (dateStr) {
            this.date = new Date(dateStr + 'T00:00:00Z');
        } else {
            this.date = new Date();
        }
        if (timeStr) {
            const parts = timeStr.split(':');
            this.date.setHours(parseInt(parts[0]) || 0);
            this.date.setMinutes(parseInt(parts[1]) || 0);
            this.date.setSeconds(parseInt(parts[2]) || 0);
        }
        // Приводим к UTC
        this.utcTime = new Date(this.date.getTime() - this.tzOffset * 3600000);
    }

    _getTzOffset() {
        return -new Date().getTimezoneOffset() / 60;
    }

    _julianDay(dt) {
        const y = dt.getUTCFullYear();
        const m = dt.getUTCMonth() + 1;
        let d = dt.getUTCDate() + dt.getUTCHours()/24 + dt.getUTCMinutes()/1440 + dt.getUTCSeconds()/86400;
        let y2 = y, m2 = m;
        if (m <= 2) { y2--; m2 += 12; }
        const A = Math.floor(y2 / 100);
        const B = 2 - A + Math.floor(A / 4);
        return Math.floor(365.25 * (y2 + 4716)) + Math.floor(30.6001 * (m2 + 1)) + d + B - 1524.5;
    }

    _solarDeclination(dt) {
        const start = new Date(Date.UTC(dt.getUTCFullYear(), 0, 1));
        const n = Math.floor((dt - start) / (1000 * 60 * 60 * 24)) + 1;
        return -23.44 * Math.cos(DEG * (360/365) * (n + 10));
    }

    _equationOfTime(dt) {
        const start = new Date(Date.UTC(dt.getUTCFullYear(), 0, 1));
        const n = Math.floor((dt - start) / (1000 * 60 * 60 * 24)) + 1;
        const B = (360/365) * (n - 81);
        const B_rad = B * DEG;
        return 9.87 * Math.sin(2 * B_rad) - 7.53 * Math.cos(B_rad) - 1.5 * Math.sin(B_rad);
    }

    calculate() {
        const jd = this._julianDay(this.utcTime);
        const eot = this._equationOfTime(this.utcTime);
        const utcHours = this.utcTime.getUTCHours() + this.utcTime.getUTCMinutes()/60 + this.utcTime.getUTCSeconds()/3600;
        const solarTime = utcHours + this.lon/15 + eot/60;
        const hourAngle = (solarTime - 12) * 15 * DEG;
        const dec = this._solarDeclination(this.utcTime) * DEG;
        const lat = this.lat;
        const sinAlt = Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(hourAngle);
        const altitude = Math.asin(sinAlt);
        let azimuth;
        if (altitude > 0.01) {
            const cosAz = (Math.sin(dec) - Math.sin(lat) * sinAlt) / (Math.cos(lat) * Math.cos(altitude) + 1e-9);
            azimuth = Math.acos(Math.max(-1, Math.min(1, cosAz)));
            if (hourAngle > 0) azimuth = -azimuth + 2 * Math.PI;
            azimuth = (azimuth + 2 * Math.PI) % (2 * Math.PI);
        } else {
            azimuth = 0;
        }
        let shadowLen = altitude > 0.01 ? this.height / Math.tan(altitude) : Infinity;
        const shadowAz = altitude > 0.01 ? (azimuth + Math.PI) % (2 * Math.PI) : 0;
        return {
            altitude: altitude * RAD,
            azimuth: azimuth * RAD,
            shadow_len: shadowLen === Infinity ? null : shadowLen,
            shadow_az: shadowAz * RAD,
            solar_time: solarTime,
            dec: dec * RAD,
            hour_angle: hourAngle * RAD,
            above_horizon: altitude > 0
        };
    }

    printResult(res, showShadow) {
        console.log(chalk.cyan('☀️ Расчёт солнечной тени:'));
        console.log(`  ${chalk.yellow('Дата/время:')} ${this.date.toISOString().replace('T', ' ').slice(0,19)}`);
        console.log(`  ${chalk.yellow('Широта:')} ${(this.lat/RAD).toFixed(4)}°, Долгота: ${this.lon.toFixed(4)}°`);
        console.log(`  ${chalk.yellow('Высота гномона:')} ${this.height.toFixed(2)} м`);
        if (res.above_horizon) {
            console.log(`  ${chalk.green('Высота Солнца:')} ${res.altitude.toFixed(2)}°`);
            console.log(`  ${chalk.green('Азимут Солнца:')} ${res.azimuth.toFixed(2)}°`);
            console.log(`  ${chalk.blue('Длина тени:')} ${res.shadow_len !== null ? res.shadow_len.toFixed(2) + ' м' : '∞'}`);
            console.log(`  ${chalk.blue('Направление тени:')} ${res.shadow_az.toFixed(2)}°`);
            if (showShadow) this._drawShadow(res);
        } else {
            console.log(chalk.red('  Солнце под горизонтом (ночь)'));
        }
    }

    _drawShadow(res) {
        const size = 15;
        const center = Math.floor(size/2);
        const scale = 2.0;
        let shadowLen = res.shadow_len !== null ? Math.min(res.shadow_len, 10) / scale : 0;
        const shadowAz = res.shadow_az * DEG;
        const x = Math.round(center + shadowLen * Math.sin(shadowAz));
        const y = Math.round(center - shadowLen * Math.cos(shadowAz));
        const grid = Array.from({length: size}, () => Array(size).fill('░'));
        grid[center][center] = '╋';
        if (x >= 0 && x < size && y >= 0 && y < size && res.shadow_len !== null) {
            const steps = Math.max(Math.abs(x-center), Math.abs(y-center));
            for (let i = 0; i <= steps; i++) {
                const cx = Math.round(center + (x-center) * i / steps);
                const cy = Math.round(center + (y-center) * i / steps);
                if (cx >= 0 && cx < size && cy >= 0 && cy < size && grid[cy][cx] === '░') {
                    grid[cy][cx] = '█';
                }
            }
        }
        console.log(chalk.cyan('\nВид сверху (N ↑):'));
        console.log('  ' + Array.from({length: size}, (_,i) => String(i).padStart(2)).join(''));
        for (let i = 0; i < size; i++) {
            let line = String(i).padStart(2) + ' ';
            for (let j = 0; j < size; j++) {
                const c = grid[i][j];
                line += c === '█' ? chalk.green('█') : c === '╋' ? chalk.white('╋') : chalk.blue('░');
            }
            console.log(line);
        }
    }

    exportJson(res, filename) {
        fs.writeFileSync(filename, JSON.stringify({
            time: this.date.toISOString(),
            lat: this.lat/RAD,
            lon: this.lon,
            height: this.height,
            result: res
        }, null, 2));
    }

    exportCsv(res, filename) {
        const header = 'time,altitude,azimuth,shadow_len,shadow_az\n';
        const row = `${this.date.toISOString()},${res.altitude},${res.azimuth},${res.shadow_len},${res.shadow_az}\n`;
        fs.writeFileSync(filename, header + row);
    }

    daySeries(stepHours = 1) {
        const results = [];
        const dt = new Date(this.date);
        dt.setUTCHours(0, 0, 0, 0);
        while (dt <= this.date && dt.getUTCHours() < 24) {
            this.utcTime = new Date(dt);
            const res = this.calculate();
            res.time = dt.toISOString();
            results.push(res);
            dt.setUTCHours(dt.getUTCHours() + stepHours);
        }
        return results;
    }
}

program
    .requiredOption('--lat <lat>', 'Широта (градусы)', parseFloat)
    .requiredOption('--lon <lon>', 'Долгота (градусы)', parseFloat)
    .option('--date <date>', 'Дата (YYYY-MM-DD)')
    .option('--time <time>', 'Время (HH:MM или HH:MM:SS)')
    .option('--height <height>', 'Высота гномона (м)', parseFloat, 1.0)
    .option('--tz <tz>', 'Часовой пояс (часы от UTC)', parseFloat)
    .option('--shadow', 'Показать ASCII-визуализацию тени')
    .option('--day', 'Расчёт на весь день (почасово)')
    .option('--json <file>', 'Экспорт в JSON')
    .option('--csv <file>', 'Экспорт в CSV')
    .parse(process.argv);

const opts = program.opts();
const sundial = new Sundial(opts.lat, opts.lon, opts.date, opts.time, opts.height, opts.tz);

if (opts.day) {
    const results = sundial.daySeries();
    console.log(chalk.cyan(`📊 Тень на ${sundial.date.toISOString().split('T')[0]} (почасово):`));
    for (const r of results) {
        if (r.above_horizon) {
            console.log(`  ${r.time.slice(11,16)}  Alt: ${String(r.altitude).padStart(5)}°  Тень: ${r.shadow_len !== null ? String(r.shadow_len.toFixed(2)).padStart(6) : '   ∞'} м  Направление: ${String(r.shadow_az.toFixed(1)).padStart(6)}°`);
        } else {
            console.log(`  ${r.time.slice(11,16)}  Ночь`);
        }
    }
    if (opts.json) {
        fs.writeFileSync(opts.json, JSON.stringify(results, null, 2));
        console.log(`Экспортировано в ${opts.json}`);
    }
    if (opts.csv) {
        const header = 'time,altitude,azimuth,shadow_len,shadow_az\n';
        const rows = results.map(r => `${r.time},${r.altitude},${r.azimuth},${r.shadow_len},${r.shadow_az}`).join('\n');
        fs.writeFileSync(opts.csv, header + rows);
        console.log(`Экспортировано в ${opts.csv}`);
    }
} else {
    const res = sundial.calculate();
    sundial.printResult(res, opts.shadow);
    if (opts.json) {
        sundial.exportJson(res, opts.json);
        console.log(chalk.green(`Экспортировано в ${opts.json}`));
    }
    if (opts.csv) {
        sundial.exportCsv(res, opts.csv);
        console.log(chalk.green(`Экспортировано в ${opts.csv}`));
    }
}
