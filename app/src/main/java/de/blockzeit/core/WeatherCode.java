package de.blockzeit.core;

/**
 * Open-Meteo WMO interpretation codes, verified against https://open-meteo.com/en/docs
 * on 2026-11-05. The current table has 29 codes, including 97 (heavy thunderstorm).
 * Unlisted codes are deliberately NOT inferred from numeric ranges.
 */
public final class WeatherCode {
    public static final int MISSING = -1;

    public enum Category {
        UNKNOWN, CLEAR, MAINLY_CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, RIME_FOG,
        DRIZZLE, FREEZING_DRIZZLE, RAIN, FREEZING_RAIN, SNOW, SNOW_GRAINS,
        RAIN_SHOWERS, SNOW_SHOWERS, THUNDERSTORM, HAIL_THUNDERSTORM
    }

    /** Relative intensity within a category, not a weather warning or a cross-category risk score. */
    public enum Severity {
        NONE(0), LIGHT(1), MODERATE(2), HEAVY(3), VIOLENT(4);
        public final int marks;
        Severity(int marks) { this.marks = marks; }
    }

    public enum Condition {
        UNKNOWN(Category.UNKNOWN, Severity.NONE, "Wetter unbekannt"),
        CLEAR(Category.CLEAR, Severity.NONE, "Klarer Himmel", "Klare Nacht"),
        MAINLY_CLEAR(Category.MAINLY_CLEAR, Severity.NONE, "Überwiegend klar", "Überwiegend klare Nacht"),
        PARTLY_CLOUDY(Category.PARTLY_CLOUDY, Severity.NONE, "Teilweise bewölkt", "Teilweise bewölkte Nacht"),
        OVERCAST(Category.OVERCAST, Severity.NONE, "Bedeckt"),
        FOG(Category.FOG, Severity.NONE, "Nebel"),
        RIME_FOG(Category.RIME_FOG, Severity.NONE, "Nebel mit Reifablagerung"),
        DRIZZLE_LIGHT(Category.DRIZZLE, Severity.LIGHT, "Leichter Nieselregen"),
        DRIZZLE_MODERATE(Category.DRIZZLE, Severity.MODERATE, "Mäßiger Nieselregen"),
        DRIZZLE_DENSE(Category.DRIZZLE, Severity.HEAVY, "Dichter Nieselregen"),
        FREEZING_DRIZZLE_LIGHT(Category.FREEZING_DRIZZLE, Severity.LIGHT, "Leichter gefrierender Nieselregen"),
        FREEZING_DRIZZLE_DENSE(Category.FREEZING_DRIZZLE, Severity.HEAVY, "Dichter gefrierender Nieselregen"),
        RAIN_LIGHT(Category.RAIN, Severity.LIGHT, "Leichter Regen"),
        RAIN_MODERATE(Category.RAIN, Severity.MODERATE, "Mäßiger Regen"),
        RAIN_HEAVY(Category.RAIN, Severity.HEAVY, "Starker Regen"),
        FREEZING_RAIN_LIGHT(Category.FREEZING_RAIN, Severity.LIGHT, "Leichter gefrierender Regen"),
        FREEZING_RAIN_HEAVY(Category.FREEZING_RAIN, Severity.HEAVY, "Starker gefrierender Regen"),
        SNOW_LIGHT(Category.SNOW, Severity.LIGHT, "Leichter Schneefall"),
        SNOW_MODERATE(Category.SNOW, Severity.MODERATE, "Mäßiger Schneefall"),
        SNOW_HEAVY(Category.SNOW, Severity.HEAVY, "Starker Schneefall"),
        SNOW_GRAINS(Category.SNOW_GRAINS, Severity.NONE, "Schneegriesel"),
        RAIN_SHOWERS_LIGHT(Category.RAIN_SHOWERS, Severity.LIGHT, "Leichte Regenschauer am Tag", "Leichte Regenschauer nachts"),
        RAIN_SHOWERS_MODERATE(Category.RAIN_SHOWERS, Severity.MODERATE, "Mäßige Regenschauer am Tag", "Mäßige Regenschauer nachts"),
        RAIN_SHOWERS_VIOLENT(Category.RAIN_SHOWERS, Severity.VIOLENT, "Heftige Regenschauer am Tag", "Heftige Regenschauer nachts"),
        SNOW_SHOWERS_LIGHT(Category.SNOW_SHOWERS, Severity.LIGHT, "Leichte Schneeschauer am Tag", "Leichte Schneeschauer nachts"),
        SNOW_SHOWERS_HEAVY(Category.SNOW_SHOWERS, Severity.HEAVY, "Starke Schneeschauer am Tag", "Starke Schneeschauer nachts"),
        THUNDERSTORM(Category.THUNDERSTORM, Severity.MODERATE, "Gewitter"),
        // Also supported defensively if a provider uses an older table without 97.
        THUNDERSTORM_HEAVY(Category.THUNDERSTORM, Severity.HEAVY, "Starkes Gewitter"),
        HAIL_THUNDERSTORM_LIGHT(Category.HAIL_THUNDERSTORM, Severity.LIGHT, "Gewitter mit leichtem Hagel"),
        HAIL_THUNDERSTORM_HEAVY(Category.HAIL_THUNDERSTORM, Severity.HEAVY, "Gewitter mit starkem Hagel");

        public final Category category;
        public final Severity severity;
        private final String dayDescription, nightDescription;
        Condition(Category category, Severity severity, String description) {
            this(category, severity, description, description);
        }
        Condition(Category category, Severity severity, String day, String night) {
            this.category = category;
            this.severity = severity;
            dayDescription = day;
            nightDescription = night;
        }
    }

    private WeatherCode() { }

    public static Condition classify(int code) {
        switch (code) {
            case 0: return Condition.CLEAR;
            case 1: return Condition.MAINLY_CLEAR;
            case 2: return Condition.PARTLY_CLOUDY;
            case 3: return Condition.OVERCAST;
            case 45: return Condition.FOG;
            case 48: return Condition.RIME_FOG;
            case 51: return Condition.DRIZZLE_LIGHT;
            case 53: return Condition.DRIZZLE_MODERATE;
            case 55: return Condition.DRIZZLE_DENSE;
            case 56: return Condition.FREEZING_DRIZZLE_LIGHT;
            case 57: return Condition.FREEZING_DRIZZLE_DENSE;
            case 61: return Condition.RAIN_LIGHT;
            case 63: return Condition.RAIN_MODERATE;
            case 65: return Condition.RAIN_HEAVY;
            case 66: return Condition.FREEZING_RAIN_LIGHT;
            case 67: return Condition.FREEZING_RAIN_HEAVY;
            case 71: return Condition.SNOW_LIGHT;
            case 73: return Condition.SNOW_MODERATE;
            case 75: return Condition.SNOW_HEAVY;
            case 77: return Condition.SNOW_GRAINS;
            case 80: return Condition.RAIN_SHOWERS_LIGHT;
            case 81: return Condition.RAIN_SHOWERS_MODERATE;
            case 82: return Condition.RAIN_SHOWERS_VIOLENT;
            case 85: return Condition.SNOW_SHOWERS_LIGHT;
            case 86: return Condition.SNOW_SHOWERS_HEAVY;
            case 95: return Condition.THUNDERSTORM;
            case 96: return Condition.HAIL_THUNDERSTORM_LIGHT;
            case 97: return Condition.THUNDERSTORM_HEAVY;
            case 99: return Condition.HAIL_THUNDERSTORM_HEAVY;
            default: return Condition.UNKNOWN;
        }
    }

    public static boolean known(int code) { return classify(code) != Condition.UNKNOWN; }

    /** German accessibility text; darkness is not guessed from local time. */
    public static String description(int code, boolean isDay) {
        Condition condition = classify(code);
        return isDay ? condition.dayDescription : condition.nightDescription;
    }
}
