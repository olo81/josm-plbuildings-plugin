package org.openstreetmap.josm.plugins.plbuildings.utils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.openstreetmap.josm.plugins.plbuildings.enums.CombineNearestOneDsStrategy;
import org.openstreetmap.josm.plugins.plbuildings.enums.CombineNearestOverlappingStrategy;

public class BuildingsSessionStateManager {
    private static CombineNearestOneDsStrategy oneDsConfirmationSessionStrategy = null;
    private static CombineNearestOverlappingStrategy overlappingConfirmationSessionStrategy = null;
    private static final Map<String, TagConflictResolution> rememberedTagConflictResolutions = new HashMap<>();
    private static final Map<String, Integer> tagConflictOccurrences = new HashMap<>();
    private static final Set<String> shownFrequentTagConflictWarnings = new HashSet<>();
    private static boolean tagConflictFrequentWarningsSuppressed = false;

    public static CombineNearestOneDsStrategy getOneDsConfirmationSessionStrategy() {
        return oneDsConfirmationSessionStrategy;
    }

    public static void setOneDsConfirmationSessionStrategy(CombineNearestOneDsStrategy strategy) {
        oneDsConfirmationSessionStrategy = strategy;
    }

    public static CombineNearestOverlappingStrategy getOverlappingConfirmationSessionStrategy() {
        return overlappingConfirmationSessionStrategy;
    }

    public static void setOverlappingConfirmationSessionStrategy(CombineNearestOverlappingStrategy strategy) {
        overlappingConfirmationSessionStrategy = strategy;
    }

    private static String tagConflictStateKey(String key, String currentValue, String newValue) {
        return key + "\u0000" + currentValue + "\u0000" + newValue;
    }

    /**
     * Stores the user's resolution of the conflict pair so the same conflict can be resolved
     * automatically for the rest of the session.
     *
     * @param key conflict tag key
     * @param currentValue tag value of the existing (target) building
     * @param newValue tag value of the imported (source) building
     * @param resolution the user's decision (e.g. which value to keep)
     */
    public static void rememberTagConflictResolution(
        String key,
        String currentValue,
        String newValue,
        TagConflictResolution resolution
    ) {
        rememberedTagConflictResolutions.put(tagConflictStateKey(key, currentValue, newValue), resolution);
    }

    /**
     * @param key conflict tag key
     * @param currentValue tag value of the existing (target) building
     * @param newValue tag value of the imported (source) building
     * @return the remembered resolution of this conflict pair or null if there is none
     */
    public static TagConflictResolution getRememberedTagConflictResolution(
        String key,
        String currentValue,
        String newValue
    ) {
        return rememberedTagConflictResolutions.get(tagConflictStateKey(key, currentValue, newValue));
    }

    /**
     * Registers the next occurrence of the conflict pair.
     *
     * @param key conflict tag key
     * @param currentValue tag value of the existing (target) building
     * @param newValue tag value of the imported (source) building
     * @return total number of occurrences of this conflict pair in this session
     */
    public static int registerTagConflictOccurrence(String key, String currentValue, String newValue) {
        String stateKey = tagConflictStateKey(key, currentValue, newValue);
        int occurrences = tagConflictOccurrences.getOrDefault(stateKey, 0) + 1;
        tagConflictOccurrences.put(stateKey, occurrences);
        return occurrences;
    }

    /**
     * @return total number of occurrences of this conflict pair in this session
     */
    public static int getTagConflictOccurrences(String key, String currentValue, String newValue) {
        return tagConflictOccurrences.getOrDefault(tagConflictStateKey(key, currentValue, newValue), 0);
    }

    /**
     * @return true if the warning about this conflict pair has already been shown in this session
     */
    public static boolean isFrequentTagConflictWarningShown(String key, String currentValue, String newValue) {
        return shownFrequentTagConflictWarnings.contains(tagConflictStateKey(key, currentValue, newValue));
    }

    /**
     * Remembers that the warning about this conflict pair has been shown in this session.
     */
    public static void markFrequentTagConflictWarningShown(String key, String currentValue, String newValue) {
        shownFrequentTagConflictWarnings.add(tagConflictStateKey(key, currentValue, newValue));
    }

    /**
     * @return true if the user decided to not see warnings about repeated remembered conflicts in this session
     */
    public static boolean isTagConflictFrequentWarningsSuppressed() {
        return tagConflictFrequentWarningsSuppressed;
    }

    /**
     * Remembers that the user doesn't want to see warnings about repeated remembered conflicts in this session.
     */
    public static void suppressTagConflictFrequentWarnings() {
        tagConflictFrequentWarningsSuppressed = true;
    }

    /**
     * Clears the tag conflict state. Used mostly by tests.
     */
    public static void clearTagConflictSessionState() {
        rememberedTagConflictResolutions.clear();
        tagConflictOccurrences.clear();
        shownFrequentTagConflictWarnings.clear();
        tagConflictFrequentWarningsSuppressed = false;
    }
}
