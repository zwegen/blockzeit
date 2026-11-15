package de.blockzeit;

import de.blockzeit.core.WeatherCode;
import de.blockzeit.core.WeatherData;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** English presentation and absolute forecast slots; no requests or cache mutation. */
final class WeatherOverviewFormat {
    private WeatherOverviewFormat() { }
    static String degrees(double value) {
        return WeatherData.validTemperature(value) ? WeatherData.temperature(value).replace("°C", "°") : "—";
    }
    static String precipitationPercent(WeatherData.Sample sample, long now) {
        double probability = sample == null ? Double.NaN : sample.displayProbability(now);
        return WeatherData.validProbability(probability) ? WeatherFooter.rain(sample, now) : "—";
    }
    static String precipitationDescription(WeatherMetricIcon.Kind kind, String percent) {
        String type = kind == WeatherMetricIcon.Kind.SNOW ? "Snow" : kind == WeatherMetricIcon.Kind.MIXED ? "Rain and snow" : "Rain";
        return type + " probability " + ("—".equals(percent) ? "unavailable" : percent);
    }
    static String dailyPrecipitation(WeatherData.Day day) {
        double probability = day == null ? Double.NaN : day.precipitationProbabilityMax;
        return WeatherData.validProbability(probability) ? WeatherData.probabilityText(probability) + "%" : "—";
    }
    static String day(LocalDate today, int offset) {
        return today.plusDays(offset).format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH));
    }
    static long firstHour(long now, String timezone) {
        return Instant.ofEpochSecond(now).atZone(ZoneId.of(timezone)).truncatedTo(ChronoUnit.HOURS).plusHours(1).toEpochSecond();
    }
    static WeatherData.Hour hour(WeatherData.Sample sample, long end) {
        if (sample != null) for (WeatherData.Hour hour : sample.hours) if (hour.endsAt == end) return hour;
        return null;
    }
    static String hourLabel(long end, long first, String timezone) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime time = Instant.ofEpochSecond(end).atZone(zone);
        String label = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
        // A repeated autumn hour must stay distinguishable without shifting absolute buckets.
        for (int i = 0; i < 4; i++) {
            long other = first + i * 3600;
            if (other != end && Instant.ofEpochSecond(other).atZone(zone).toLocalDateTime().equals(time.toLocalDateTime()))
                return label + "\nUTC" + time.getOffset();
        }
        return label;
    }
    static String description(int code, boolean day) {
        return switch (WeatherCode.classify(code)) {
            case CLEAR -> day ? "Clear sky" : "Clear night";
            case MAINLY_CLEAR -> "Mainly clear";
            case PARTLY_CLOUDY -> "Partly cloudy";
            case OVERCAST -> "Overcast";
            case FOG -> "Fog";
            case RIME_FOG -> "Rime fog";
            case DRIZZLE_LIGHT -> "Light drizzle";
            case DRIZZLE_MODERATE -> "Moderate drizzle";
            case DRIZZLE_DENSE -> "Dense drizzle";
            case FREEZING_DRIZZLE_LIGHT -> "Light freezing drizzle";
            case FREEZING_DRIZZLE_DENSE -> "Dense freezing drizzle";
            case RAIN_LIGHT -> "Light rain";
            case RAIN_MODERATE -> "Moderate rain";
            case RAIN_HEAVY -> "Heavy rain";
            case FREEZING_RAIN_LIGHT -> "Light freezing rain";
            case FREEZING_RAIN_HEAVY -> "Heavy freezing rain";
            case SNOW_LIGHT -> "Light snowfall";
            case SNOW_MODERATE -> "Moderate snowfall";
            case SNOW_HEAVY -> "Heavy snowfall";
            case SNOW_GRAINS -> "Snow grains";
            case RAIN_SHOWERS_LIGHT -> "Light rain showers";
            case RAIN_SHOWERS_MODERATE -> "Moderate rain showers";
            case RAIN_SHOWERS_VIOLENT -> "Violent rain showers";
            case SNOW_SHOWERS_LIGHT -> "Light snow showers";
            case SNOW_SHOWERS_HEAVY -> "Heavy snow showers";
            case THUNDERSTORM -> "Thunderstorm";
            case THUNDERSTORM_HEAVY -> "Heavy thunderstorm";
            case HAIL_THUNDERSTORM_LIGHT -> "Thunderstorm with light hail";
            case HAIL_THUNDERSTORM_HEAVY -> "Thunderstorm with heavy hail";
            default -> "Weather unavailable";
        };
    }
}
