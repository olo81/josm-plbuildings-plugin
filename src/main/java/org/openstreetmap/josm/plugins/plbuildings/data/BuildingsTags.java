package org.openstreetmap.josm.plugins.plbuildings.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildingsTags {

    public static final Set<String> LIVING_BUILDINGS = Set.of(
        "house", "apartments", "detached", "semidetached_house", "terrace", "residential"
    );

    public static final Set<String> HOUSE_DETAILS = Set.of("detached", "semidetached_house", "terrace");

    /*
    Tags extracted from
    https://github.com/openstreetmap-polska/gugik2osm/blob/main/processing/sql/data/buildings_categories_mappings.csv
    4th column and filtered

    and added "construction"
    and added some LIVING_BUILDING

    2022-07-30
    They not need to be checked by mapper
     */
    public static final Set<String> DEFAULT_COMMON_BUILDING_VALUES = Collections.unmodifiableSet(
        Stream.concat(
            Set.of(
                "bungalow",
                "cabin",
                "commercial",
                "farmhouse",
                "garage",
                "hangar",
                "house",
                "industrial",
                "outbuilding",
                "retail",
                "service",
                "warehouse",
                "shed",
                "yes",

                "construction"
            ).stream(),
            LIVING_BUILDINGS.stream()
        ).collect(Collectors.toSet())
    );

    // Some imported data can contain other tags than building, it should be checked by mapper
    public static final List<String> UNCOMMON_NO_BUILDING_TAGS = Arrays.asList(
        "amenity",
        "leisure",
        "historic",
        "tourism"
    );

    /**
     * Keys which describe a service located in the building (a shop, an office, a pharmacy etc.).
     * When several parts of one block are replaced by a single building, each such tag is extracted
     * to a separate node instead of being lost with the deleted part.
     */
    public static final Set<String> SERVICE_TAGS = Set.of(
        "amenity",
        "club",
        "craft",
        "education",
        "emergency",
        "healthcare",
        "historic",
        "leisure",
        "office",
        "public_transport",
        "shop",
        "social_facility",
        "sport",
        "tourism"
    );

    /**
     * https://wiki.openstreetmap.org/wiki/Lifecycle_prefix
     */
    public static final Set<String> COMMON_LIFECYCLE_PREFIXES = Set.of(
        "proposed", "planned", "construction", "abandoned", "ruins", "demolished", "destroyed", "removed", "was"
    );

}
