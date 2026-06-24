package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.enums.DataClassification;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Document 22 — Data Classification is derived from the object category in one place,
 * so a new category maps to a level without touching core logic (extensibility), and
 * unknown categories fail safe to RESTRICTED.
 */
class DataClassificationTest {

    @Test
    void categoriesMapToSensibleLevels() {
        assertThat(DataClassification.forCategory(ObjectCategory.MEDICAL)).isEqualTo(DataClassification.CONFIDENTIAL);
        assertThat(DataClassification.forCategory(ObjectCategory.INFRASTRUCTURE)).isEqualTo(DataClassification.RESTRICTED);
        assertThat(DataClassification.forCategory(ObjectCategory.RETAIL)).isEqualTo(DataClassification.PUBLIC);
        assertThat(DataClassification.forCategory(ObjectCategory.GENERAL)).isEqualTo(DataClassification.PUBLIC);
    }

    @Test
    void unknownOrNullFailsSafeToRestricted() {
        assertThat(DataClassification.forCategory(ObjectCategory.UNKNOWN)).isEqualTo(DataClassification.RESTRICTED);
        assertThat(DataClassification.forCategory(null)).isEqualTo(DataClassification.RESTRICTED);
    }
}
