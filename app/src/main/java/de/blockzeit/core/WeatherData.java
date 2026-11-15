package de.blockzeit.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/** Immutable, location-local weather values; timestamps are epoch seconds, never milliseconds. */
public final class WeatherData {
    public static final long INTERVAL_SECONDS = 20 * 60;
    // The overview uses only the first compact hourly section, not five full hourly days.
    public static final int MAX_HOURS = 64;
    public static final int MAX_DAYS = 5;
    private WeatherData() { }

    public static final class Place {
        public final String name, region, country, timezone;
        public final double latitude, longitude;
        public Place(String name, String region, String country, String timezone, double latitude, double longitude) {
            this.name = clean(name); this.region = clean(region); this.country = clean(country);
            if (this.name.isEmpty() || !Double.isFinite(latitude) || Math.abs(latitude) > 90
                    || !Double.isFinite(longitude) || Math.abs(longitude) > 180
                    || timezone == null || !ZoneId.getAvailableZoneIds().contains(timezone)) {
                throw new IllegalArgumentException("Invalid weather location");
            }
            this.timezone = timezone; this.latitude = latitude; this.longitude = longitude;
        }
        public String label() {
            List<String> parts = new ArrayList<>();
            parts.add(name);
            if (!region.isEmpty()) parts.add(region);
            if (!country.isEmpty()) parts.add(country);
            return String.join(" · ", parts);
        }
        private static String clean(String text) {
            String result = text == null ? "" : text.trim();
            if (result.length() > 200 || result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0)
                throw new IllegalArgumentException("Invalid location label");
            return result;
        }
    }

    /** Probability applies to the preceding hour ending at this absolute epoch second. */
    public static final class Hour {
        public final long endsAt;
        public final double probability, temperature, rain, showers, snowfall;
        public final int code;
        public Hour(long endsAt, double probability) {
            this(endsAt, probability, -1, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        public Hour(long endsAt, double probability, int code, double temperature,
                double rain, double showers, double snowfall) {
            this.endsAt = endsAt;
            this.probability = validProbability(probability) ? probability : Double.NaN;
            this.code = knownCode(code) ? code : -1;
            this.temperature = validTemperature(temperature) ? temperature : Double.NaN;
            this.rain = amount(rain); this.showers = amount(showers); this.snowfall = amount(snowfall);
        }
        private static double amount(double value) {
            // Generous hourly sanity bound; corrupt giant finite amounts are not a snow signal.
            return Double.isFinite(value) && value >= 0 && value <= 1000 ? value : Double.NaN;
        }
        public boolean hasPrecipitationSource() {
            return code != -1 || validTemperature(temperature) || rain > 0 || showers > 0 || snowfall > 0;
        }
        private Hour merge(Hour other) {
            return new Hour(endsAt, same(probability, other.probability), code == other.code ? code : -1,
                    same(temperature, other.temperature), same(rain, other.rain),
                    same(showers, other.showers), same(snowfall, other.snowfall));
        }
        private static double same(double a, double b) { return a == b ? a : Double.NaN; }
    }

    public enum Precipitation { RAIN, SNOW, MIXED, UNKNOWN }

    /** Forecast evidence only: never infer a precipitation type from temperature or season. */
    public static Precipitation forecastPrecipitation(Hour hour) {
        if (hour == null) return Precipitation.UNKNOWN;
        boolean liquid = hour.rain > 0 || hour.showers > 0, snow = hour.snowfall > 0;
        if (liquid || snow) return liquid && snow ? Precipitation.MIXED : snow ? Precipitation.SNOW : Precipitation.RAIN;
        switch (hour.code) {
            case 51: case 53: case 55: case 56: case 57: case 61: case 63: case 65:
            case 66: case 67: case 80: case 81: case 82: case 95: case 96: case 97: case 99:
                return Precipitation.RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86: return Precipitation.SNOW;
            default: return Precipitation.UNKNOWN;
        }
    }

    /** Only the selected probability bucket may supply the type or temperature fallback. */
    public static Precipitation precipitation(Hour hour) {
        if (hour == null) return Precipitation.RAIN;
        // These amounts describe the same preceding hour as the probability. They also
        // represent simultaneous rain + snow, for which Open-Meteo has no mixed WMO code.
        boolean liquid = hour.rain > 0 || hour.showers > 0, snow = hour.snowfall > 0;
        if (liquid || snow) return liquid && snow ? Precipitation.MIXED : snow ? Precipitation.SNOW : Precipitation.RAIN;
        switch (hour.code) {
            case 51: case 53: case 55: case 56: case 57: case 61: case 63: case 65:
            case 66: case 67: case 80: case 81: case 82: case 95: case 96: case 97: case 99:
                // Freezing rain/drizzle and thunderstorm hail are not snowfall.
                return Precipitation.RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86: return Precipitation.SNOW;
            default:
                // Clear/cloud/fog (including rime fog) do not specify a precipitation type.
                if (validTemperature(hour.temperature)) {
                    if (hour.temperature <= 0) return Precipitation.SNOW;
                    if (hour.temperature <= 2) return Precipitation.MIXED;
                }
                // Missing/invalid temperature, including legacy cache: do not invent snow.
                return Precipitation.RAIN;
        }
    }

    /** A place-local calendar label, not a midnight epoch or a rolling 24-hour interval. */
    public static final class Day {
        public final String date;
        public final double maximum, minimum;
        /** Highest hourly precipitation probability of this local day; not an all-day event probability. */
        public final double precipitationProbabilityMax;
        public Day(String date, double maximum, double minimum) {
            this(date, maximum, minimum, Double.NaN);
        }
        public Day(String date, double maximum, double minimum, double precipitationProbabilityMax) {
            this.date = LocalDate.parse(date).toString();
            double max = validTemperature(maximum) ? maximum : Double.NaN;
            double min = validTemperature(minimum) ? minimum : Double.NaN;
            if (Double.isFinite(max) && Double.isFinite(min) && max < min) {
                max = Double.NaN; min = Double.NaN;
            }
            this.maximum = max; this.minimum = min;
            this.precipitationProbabilityMax = validProbability(precipitationProbabilityMax) ? precipitationProbabilityMax : Double.NaN;
        }
        private Day merge(Day other) {
            // Conflicting duplicate labels are not an arbitrary choice of extrema.
            return new Day(date, maximum == other.maximum ? maximum : Double.NaN,
                    minimum == other.minimum ? minimum : Double.NaN,
                    precipitationProbabilityMax == other.precipitationProbabilityMax ? precipitationProbabilityMax : Double.NaN);
        }
    }

    public static final class Sample {
        public final double temperature, maximum, minimum, windSpeed;
        public final List<Hour> hours;
        public final List<Day> days;
        public final Hour retainedHour;
        public final int code;
        public final boolean isDay;
        public final long fetchedAt;
        public final String day;
        public Sample(double temperature, int code, boolean isDay, long fetchedAt, String day,
                double maximum, double minimum) {
            this(temperature, code, isDay, fetchedAt, day, maximum, minimum, Double.NaN, Collections.emptyList());
        }
        public Sample(double temperature, int code, boolean isDay, long fetchedAt, String day,
                double maximum, double minimum, double windSpeed, List<Hour> hours) {
            this(temperature, code, isDay, fetchedAt, day, maximum, minimum, windSpeed, hours, null);
        }
        public Sample(double temperature, int code, boolean isDay, long fetchedAt, String day,
                double maximum, double minimum, double windSpeed, List<Hour> hours, Hour retainedHour) {
            this(temperature, code, isDay, fetchedAt, day, maximum, minimum, windSpeed, hours, retainedHour,
                    Collections.emptyList());
        }
        public Sample(double temperature, int code, boolean isDay, long fetchedAt, String day,
                double maximum, double minimum, double windSpeed, List<Hour> hours, Hour retainedHour, List<Day> days) {
            if (!validTemperature(temperature) || fetchedAt <= 0)
                throw new IllegalArgumentException("Invalid weather sample");
            this.temperature = temperature; this.code = knownCode(code) ? code : -1;
            this.isDay = isDay; this.fetchedAt = fetchedAt;
            this.day = LocalDate.parse(day).toString();
            this.maximum = validTemperature(maximum) ? maximum : Double.NaN;
            this.minimum = validTemperature(minimum) ? minimum : Double.NaN;
            this.windSpeed = validWindSpeed(windSpeed) ? windSpeed : Double.NaN;
            TreeMap<Long, Hour> sorted = new TreeMap<>();
            if (hours != null) for (Hour hour : hours) {
                if (hour == null || hour.endsAt <= 0 || hour.endsAt > 253402300799L) continue;
                Hour previous = sorted.get(hour.endsAt);
                // Conflicting duplicate buckets are unknown, never an arbitrary probability.
                sorted.put(hour.endsAt, previous == null ? hour : previous.merge(hour));
                if (sorted.size() > MAX_HOURS) sorted.pollLastEntry();
            }
            this.hours = Collections.unmodifiableList(new ArrayList<>(sorted.values()));
            TreeMap<String, Day> dated = new TreeMap<>();
            LocalDate first = LocalDate.parse(this.day), last = first.plusDays(MAX_DAYS - 1);
            if (days != null) for (Day entry : days) {
                if (entry == null) continue;
                LocalDate date = LocalDate.parse(entry.date);
                if (date.isBefore(first) || date.isAfter(last)) continue;
                Day previous = dated.get(entry.date);
                dated.put(entry.date, previous == null ? entry : previous.merge(entry));
            }
            this.days = Collections.unmodifiableList(new ArrayList<>(dated.values()));
            this.retainedHour = retainedHour != null && validProbability(retainedHour.probability) ? retainedHour : null;
        }
        /** Exact date lookup only; missing days never inherit another day's extrema. */
        public Day day(String date) {
            for (Day entry : days) if (entry.date.equals(date)) return entry;
            return null;
        }
        /** First strictly future endpoint; a missing/unknown nearer bucket cannot be skipped. */
        public Hour hour(long now) {
            for (Hour hour : hours) if (hour.endsAt > now)
                return now >= hour.endsAt - 3600 ? hour : null;
            return null;
        }
        /** Retain a coherent probability/type bucket when its time window expires or is missing.
         * Strict hour() remains available for freshness/forecast semantics, never relabels this source. */
        public Hour displayHour(long now) {
            Hour previous = retainedHour;
            // Legacy/first samples may have no explicit retained bucket. Discover coherent old
            // data before deciding whether an incomplete current bucket can replace its icon.
            for (Hour hour : hours) if (hour.endsAt <= now && validProbability(hour.probability)
                    && (previous == null || hour.hasPrecipitationSource() && !previous.hasPrecipitationSource()
                    || hour.hasPrecipitationSource() == previous.hasPrecipitationSource() && hour.endsAt > previous.endsAt)) previous = hour;
            Hour current = hour(now);
            if (current != null && validProbability(current.probability)
                    && (previous == null || !previous.hasPrecipitationSource() || current.hasPrecipitationSource())) return current;
            return previous;
        }
        public double displayProbability(long now) {
            Hour hour = displayHour(now);
            return hour == null ? Double.NaN : hour.probability;
        }
        public double probability(long now) {
            Hour hour = hour(now);
            return hour == null ? Double.NaN : hour.probability;
        }
        public boolean stale(long now) { return now < fetchedAt || now - fetchedAt >= INTERVAL_SECONDS; }
        public boolean today(long now, String timezone) {
            try { return day.equals(localDay(now, timezone)); }
            catch (RuntimeException invalid) { return false; }
        }
    }
    public static String localDay(long now, String timezone) {
        return Instant.ofEpochSecond(now).atZone(ZoneId.of(timezone)).toLocalDate().toString();
    }
    public static boolean validTemperature(double value) { return Double.isFinite(value) && value >= -150 && value <= 100; }
    /** Generous physical sanity bound for 10 m wind, in explicit km/h only. */
    public static boolean validWindSpeed(double value) { return Double.isFinite(value) && value >= 0 && value <= 500; }
    public static boolean validProbability(double value) { return Double.isFinite(value) && value >= 0 && value <= 100; }
    /** Open-Meteo's WMO table, not a numeric range: unlisted codes must remain unknown. */
    public static boolean knownCode(int code) {
        switch (code) {
            case 0: case 1: case 2: case 3: case 45: case 48: case 51: case 53: case 55:
            case 56: case 57: case 61: case 63: case 65: case 66: case 67: case 71:
            case 73: case 75: case 77: case 80: case 81: case 82: case 85: case 86:
            case 95: case 96: case 97: case 99: return true;
            default: return false;
        }
    }
    public static String temperature(double value) {
        if (!validTemperature(value)) return "—";
        return rounded(value, 1) + "°C";
    }
    public static String windText(double value) { return validWindSpeed(value) ? rounded(value, 0) : "—"; }
    public static String probabilityText(double value) { return validProbability(value) ? rounded(value, 0) : "—"; }
    private static String rounded(double value, int scale) {
        BigDecimal result = BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
        // BigDecimal has no negative zero; sign is selected after decimal rounding.
        return result.toPlainString().replace('-', '−');
    }
}
