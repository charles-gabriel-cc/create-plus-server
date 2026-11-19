package com.createplus.bossarenaguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

record ArenaProfile(double radius, double lowerOffset, double upperOffset, boolean dimensionHeight, Integer ceilingY) {
    ArenaProfile(double radius, double lowerOffset, double upperOffset) {
        this(radius, lowerOffset, upperOffset, false, null);
    }

    static ArenaProfile configured(int radius, int lower, int upper, String mode, Integer ceiling) {
        boolean dimension = "dimension".equals(mode);
        if (radius < 32 || radius > 512
                || (!dimension && !"bounded".equals(mode))
                || (dimension && (lower != 0 || upper != 0 || ceiling != null))
                || (!dimension && (lower < -512 || lower > -4 || upper < 4 || upper > 512))
                || (ceiling != null && (ceiling < -2048 || ceiling > 2048))) {
            throw new IllegalArgumentException("Invalid arena dimensions");
        }
        return new ArenaProfile(radius, lower, upper, dimension, ceiling);
    }

    static Map<String, ArenaProfile> loadDefaults() {
        Map<String, ArenaProfile> profiles = new HashMap<>();
        try (var resource = ArenaProfile.class.getResourceAsStream("/boss-arena-profiles.csv")) {
            if (resource == null) {
                throw new IllegalStateException("Missing boss-arena-profiles.csv");
            }
            try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
                reader.readLine(); // CSV header
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split(",", -1);
                    Integer ceiling = fields[5].isEmpty() ? null : Integer.valueOf(fields[5]);
                    ArenaProfile profile = configured(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                            Integer.parseInt(fields[3]), fields[4], ceiling);
                    if (profiles.putIfAbsent(fields[0], profile) != null) {
                        throw new IllegalStateException("Duplicate default arena profile: " + fields[0]);
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read default arena profiles", ex);
        }
        return Map.copyOf(profiles);
    }
}
