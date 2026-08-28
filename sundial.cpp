// sundial.cpp
#include <iostream>
#include <string>
#include <vector>
#include <cmath>
#include <ctime>
#include <iomanip>
#include <fstream>
#include <sstream>
#include <json/json.h> // using jsoncpp

using namespace std;

const double DEG = M_PI / 180.0;
const double RAD = 180.0 / M_PI;

struct Result {
    double altitude, azimuth, shadow_az, solar_time, dec, hour_angle;
    bool has_shadow_len;
    double shadow_len;
    bool above_horizon;
};

class Sundial {
private:
    double lat, lon, height, tz_offset;
    time_t date_time;
    time_t utc_time;

    double julianDay(time_t t) {
        tm* tm = gmtime(&t);
        int y = tm->tm_year + 1900;
        int m = tm->tm_mon + 1;
        double d = tm->tm_mday + tm->tm_hour/24.0 + tm->tm_min/1440.0 + tm->tm_sec/86400.0;
        if (m <= 2) { y--; m += 12; }
        int A = y / 100;
        int B = 2 - A + A / 4;
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + B - 1524.5;
    }

    double solarDeclination(time_t t) {
        tm* tm = gmtime(&t);
        time_t start = timegm(&tm);
        tm tm_start = {0}; tm_start.tm_year = tm->tm_year; tm_start.tm_mon = 0; tm_start.tm_mday = 1;
        double n = difftime(t, timegm(&tm_start)) / 86400 + 1;
        return -23.44 * cos(DEG * (360.0/365.0) * (n + 10));
    }

    double equationOfTime(time_t t) {
        tm* tm = gmtime(&t);
        tm tm_start = {0}; tm_start.tm_year = tm->tm_year; tm_start.tm_mon = 0; tm_start.tm_mday = 1;
        double n = difftime(t, timegm(&tm_start)) / 86400 + 1;
        double B = (360.0 / 365.0) * (n - 81);
        double B_rad = B * DEG;
        return 9.87 * sin(2 * B_rad) - 7.53 * cos(B_rad) - 1.5 * sin(B_rad);
    }

public:
    Sundial(double lat, double lon, double height, double tz, const string& dateStr, const string& timeStr) {
        this->lat = lat * DEG;
        this->lon = lon;
        this->height = height;
        if (tz != 0) this->tz_offset = tz;
        else { this->tz_offset = -timezone / 3600; }
        time_t t = time(nullptr);
        if (!dateStr.empty()) {
            tm tm = {0}; strptime(dateStr.c_str(), "%Y-%m-%d", &tm);
            t = timegm(&tm);
        }
        if (!timeStr.empty()) {
            int h, m, s = 0;
            sscanf(timeStr.c_str(), "%d:%d:%d", &h, &m, &s);
            tm* tm = gmtime(&t);
            tm->tm_hour = h; tm->tm_min = m; tm->tm_sec = s;
            t = timegm(tm);
        }
        date_time = t;
        utc_time = t - (time_t)(tz_offset * 3600);
    }

    Result calculate() {
        double eot = equationOfTime(utc_time);
        tm* tm = gmtime(&utc_time);
        double utcHours = tm->tm_hour + tm->tm_min/60.0 + tm->tm_sec/3600.0;
        double solarTime = utcHours + lon/15.0 + eot/60.0;
        double hourAngle = (solarTime - 12) * 15 * DEG;
        double dec = solarDeclination(utc_time) * DEG;
        double sinAlt = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle);
        double altitude = asin(sinAlt);
        double azimuth = 0;
        if (altitude > 0.01) {
            double cosAz = (sin(dec) - sin(lat) * sinAlt) / (cos(lat) * cos(altitude) + 1e-9);
            azimuth = acos(max(-1.0, min(1.0, cosAz)));
            if (hourAngle > 0) azimuth = -azimuth + 2*M_PI;
            azimuth = fmod(azimuth + 2*M_PI, 2*M_PI);
        }
        Result res;
        res.altitude = altitude * RAD;
        res.azimuth = azimuth * RAD;
        res.has_shadow_len = altitude > 0.01;
        res.shadow_len = res.has_shadow_len ? height / tan(altitude) : 0;
        res.shadow_az = res.has_shadow_len ? fmod(azimuth + M_PI, 2*M_PI) * RAD : 0;
        res.solar_time = solarTime;
        res.dec = dec * RAD;
        res.hour_angle = hourAngle * RAD;
        res.above_horizon = altitude > 0;
        return res;
    }

    void printResult(const Result& res, bool shadow, bool color) {
        string c = color ? "\033[36m" : "", cY = color ? "\033[33m" : "", cG = color ? "\033[32m" : "", cB = color ? "\033[34m" : "", cR = color ? "\033[31m" : "", reset = color ? "\033[0m" : "";
        cout << c << "☀️ Расчёт солнечной тени:" << reset << endl;
        char buf[20]; strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", gmtime(&date_time));
        cout << "  " << cY << "Дата/время:" << reset << " " << buf << endl;
        cout << "  " << cY << "Широта:" << reset << " " << fixed << setprecision(4) << lat/RAD << "°, Долгота: " << lon << "°" << endl;
        cout << "  " << cY << "Высота гномона:" << reset << " " << fixed << setprecision(2) << height << " м" << endl;
        if (res.above_horizon) {
            cout << "  " << cG << "Высота Солнца:" << reset << " " << fixed << setprecision(2) << res.altitude << "°" << endl;
            cout << "  " << cG << "Азимут Солнца:" << reset << " " << fixed << setprecision(2) << res.azimuth << "°" << endl;
            if (res.has_shadow_len) {
                cout << "  " << cB << "Длина тени:" << reset << " " << fixed << setprecision(2) << res.shadow_len << " м" << endl;
            } else {
                cout << "  " << c << "Тень бесконечна (Солнце в зените)" << reset << endl;
            }
            cout << "  " << cB << "Направление тени:" << reset << " " << fixed << setprecision(2) << res.shadow_az << "°" << endl;
            if (shadow) drawShadow(res);
        } else {
            cout << cR << "  Солнце под горизонтом (ночь)" << reset << endl;
        }
    }

    void drawShadow(const Result& res) {
        int size = 15, center = size/2;
        double scale = 2.0;
        double shadowLen = res.has_shadow_len ? min(res.shadow_len, 10.0) / scale : 0;
        double shadowAz = res.shadow_az * DEG;
        int x = (int)round(center + shadowLen * sin(shadowAz));
        int y = (int)round(center - shadowLen * cos(shadowAz));
        vector<vector<char>> grid(size, vector<char>(size, '░'));
        grid[center][center] = '╋';
        if (x >= 0 && x < size && y >= 0 && y < size && res.has_shadow_len) {
            int steps = max(abs(x-center), abs(y-center));
            for (int i = 0; i <= steps; i++) {
                int cx = (int)round(center + (double)(x-center) * i / steps);
                int cy = (int)round(center + (double)(y-center) * i / steps);
                if (cx >= 0 && cx < size && cy >= 0 && cy < size && grid[cy][cx] == '░')
                    grid[cy][cx] = '█';
            }
        }
        cout << "\n\033[36mВид сверху (N ↑):\033[0m" << endl;
        cout << "  ";
        for (int i = 0; i < size; i++) cout << setw(2) << i;
        cout << endl;
        for (int i = 0; i < size; i++) {
            cout << setw(2) << i << " ";
            for (int j = 0; j < size; j++) {
                char c = grid[i][j];
                if (c == '█') cout << "\033[32m█\033[0m";
                else if (c == '╋') cout << "\033[37m╋\033[0m";
                else cout << "\033[34m░\033[0m";
            }
            cout << endl;
        }
    }

    void exportJSON(const Result& res, const string& filename) {
        Json::Value root;
        root["time"] = (double)date_time;
        root["lat"] = lat/RAD;
        root["lon"] = lon;
        root["height"] = height;
        root["result"]["altitude"] = res.altitude;
        root["result"]["azimuth"] = res.azimuth;
        root["result"]["shadow_len"] = res.has_shadow_len ? res.shadow_len : 0;
        root["result"]["shadow_az"] = res.shadow_az;
        root["result"]["above_horizon"] = res.above_horizon;
        ofstream ofs(filename);
        ofs << root.toStyledString();
    }

    void exportCSV(const Result& res, const string& filename) {
        ofstream ofs(filename);
        ofs << "time,altitude,azimuth,shadow_len,shadow_az\n";
        char buf[20]; strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", gmtime(&date_time));
        ofs << buf << "," << res.altitude << "," << res.azimuth << ","
            << (res.has_shadow_len ? to_string(res.shadow_len) : "") << "," << res.shadow_az << "\n";
    }

    vector<map<string, double>> daySeries() {
        vector<map<string, double>> results;
        time_t t = date_time;
        tm* tm = gmtime(&t);
        tm->tm_hour = 0; tm->tm_min = 0; tm->tm_sec = 0;
        t = timegm(tm);
        for (int h = 0; h < 24; h++) {
            time_t dt = t + h * 3600;
            Sundial tmp(lat/RAD, lon, height, tz_offset, "", "");
            tmp.date_time = dt;
            tmp.utc_time = dt - (time_t)(tz_offset * 3600);
            Result res = tmp.calculate();
            map<string, double> entry;
            entry["time"] = dt;
            entry["altitude"] = res.altitude;
            entry["azimuth"] = res.azimuth;
            entry["shadow_len"] = res.has_shadow_len ? res.shadow_len : -1;
            entry["shadow_az"] = res.shadow_az;
            entry["above_horizon"] = res.above_horizon ? 1 : 0;
            results.push_back(entry);
        }
        return results;
    }
};

int main(int argc, char* argv[]) {
    double lat = 0, lon = 0, height = 1.0, tz = 0;
    string dateStr, timeStr, jsonFile, csvFile;
    bool shadow = false, day = false, color = false;

    for (int i = 1; i < argc; ++i) {
        string arg = argv[i];
        if (arg == "--lat" && i+1 < argc) lat = stod(argv[++i]);
        else if (arg == "--lon" && i+1 < argc) lon = stod(argv[++i]);
        else if (arg == "--date" && i+1 < argc) dateStr = argv[++i];
        else if (arg == "--time" && i+1 < argc) timeStr = argv[++i];
        else if (arg == "--height" && i+1 < argc) height = stod(argv[++i]);
        else if (arg == "--tz" && i+1 < argc) tz = stod(argv[++i]);
        else if (arg == "--shadow") shadow = true;
        else if (arg == "--day") day = true;
        else if (arg == "--json" && i+1 < argc) jsonFile = argv[++i];
        else if (arg == "--csv" && i+1 < argc) csvFile = argv[++i];
        else if (arg == "--color") color = true;
    }

    if (lat == 0 && lon == 0) {
        cerr << "Ошибка: --lat и --lon обязательны" << endl;
        return 1;
    }
    color = color || isatty(fileno(stdout));

    Sundial sundial(lat, lon, height, tz, dateStr, timeStr);

    if (day) {
        auto results = sundial.daySeries();
        char buf[20]; strftime(buf, sizeof(buf), "%Y-%m-%d", gmtime(&sundial.date_time));
        cout << "\033[36m📊 Тень на " << buf << " (почасово):\033[0m" << endl;
        for (auto& r : results) {
            if (r["above_horizon"] > 0.5) {
                string sl = r["shadow_len"] >= 0 ? to_string((int)r["shadow_len"]) : "∞";
                char tb[20]; time_t t = (time_t)r["time"]; strftime(tb, sizeof(tb), "%H:%M", gmtime(&t));
                cout << "  " << tb << "  Alt: " << setw(5) << fixed << setprecision(1) << r["altitude"]
                     << "°  Тень: " << setw(6) << sl << " м  Направление: " << setw(6) << fixed << setprecision(1) << r["shadow_az"] << "°" << endl;
            } else {
                char tb[20]; time_t t = (time_t)r["time"]; strftime(tb, sizeof(tb), "%H:%M", gmtime(&t));
                cout << "  " << tb << "  Ночь" << endl;
            }
        }
        if (!jsonFile.empty()) {
            Json::Value root(Json::arrayValue);
            for (auto& r : results) {
                Json::Value item;
                item["time"] = (double)r["time"];
                item["altitude"] = r["altitude"];
                item["azimuth"] = r["azimuth"];
                item["shadow_len"] = r["shadow_len"];
                item["shadow_az"] = r["shadow_az"];
                item["above_horizon"] = r["above_horizon"] > 0.5;
                root.append(item);
            }
            ofstream ofs(jsonFile);
            ofs << root.toStyledString();
            cout << "Экспортировано в " << jsonFile << endl;
        }
        if (!csvFile.empty()) {
            ofstream ofs(csvFile);
            ofs << "time,altitude,azimuth,shadow_len,shadow_az\n";
            for (auto& r : results) {
                char tb[20]; time_t t = (time_t)r["time"]; strftime(tb, sizeof(tb), "%Y-%m-%d %H:%M:%S", gmtime(&t));
                ofs << tb << "," << r["altitude"] << "," << r["azimuth"] << ","
                    << (r["shadow_len"] >= 0 ? to_string(r["shadow_len"]) : "") << "," << r["shadow_az"] << "\n";
            }
            cout << "Экспортировано в " << csvFile << endl;
        }
    } else {
        Result res = sundial.calculate();
        sundial.printResult(res, shadow, color);
        if (!jsonFile.empty()) {
            sundial.exportJSON(res, jsonFile);
            cout << "\033[32mЭкспортировано в " << jsonFile << "\033[0m" << endl;
        }
        if (!csvFile.empty()) {
            sundial.exportCSV(res, csvFile);
            cout << "\033[32mЭкспортировано в " << csvFile << "\033[0m" << endl;
        }
    }
    return 0;
}
