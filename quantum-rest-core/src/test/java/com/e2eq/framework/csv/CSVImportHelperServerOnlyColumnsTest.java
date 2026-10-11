package com.e2eq.framework.csv;

import jakarta.validation.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV columns bind through bean setters rather than Jackson, so the import must refuse
 * server-only control properties itself.
 */
class CSVImportHelperServerOnlyColumnsTest {

    @Test
    void rejects_skipValidation_column() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> CSVImportHelper.rejectServerOnlyColumns(new String[]{"refName", "skipValidation"}));
        assertTrue(ex.getMessage().contains("skipValidation"), ex.getMessage());
    }

    @Test
    void rejects_skipValidation_regardless_of_case_or_index_suffix() {
        assertThrows(ValidationException.class,
                () -> CSVImportHelper.rejectServerOnlyColumns(new String[]{"SKIPVALIDATION"}));
        assertThrows(ValidationException.class,
                () -> CSVImportHelper.rejectServerOnlyColumns(new String[]{"child.skipValidation[0]"}));
    }

    @Test
    void accepts_ordinary_columns() {
        assertDoesNotThrow(() -> CSVImportHelper.rejectServerOnlyColumns(
                new String[]{"refName", "dataDomain.ownerId", "tags[0]", null}));
        assertDoesNotThrow(() -> CSVImportHelper.rejectServerOnlyColumns(null));
    }
}
