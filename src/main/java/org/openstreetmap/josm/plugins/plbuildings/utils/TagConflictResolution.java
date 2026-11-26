package org.openstreetmap.josm.plugins.plbuildings.utils;

import org.openstreetmap.josm.gui.conflict.tags.MultiValueDecisionType;

/**
 * The user's resolution of a single tag conflict, remembered for the rest of the session.
 */
public class TagConflictResolution {
    private final MultiValueDecisionType decisionType;
    private final String chosenValue;

    /**
     * @param decisionType the type of the decision made by the user
     * @param chosenValue the value chosen by the user (used only for {@link MultiValueDecisionType#KEEP_ONE})
     */
    public TagConflictResolution(MultiValueDecisionType decisionType, String chosenValue) {
        this.decisionType = decisionType;
        this.chosenValue = chosenValue;
    }

    public MultiValueDecisionType getDecisionType() {
        return decisionType;
    }

    public String getChosenValue() {
        return chosenValue;
    }
}
